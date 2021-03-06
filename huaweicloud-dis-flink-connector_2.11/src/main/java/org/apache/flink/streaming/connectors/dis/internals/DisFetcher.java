/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.streaming.connectors.dis.internals;

import com.huaweicloud.dis.adapter.kafka.clients.consumer.ConsumerRecord;
import com.huaweicloud.dis.adapter.kafka.clients.consumer.ConsumerRecords;
import com.huaweicloud.dis.adapter.kafka.clients.consumer.OffsetAndMetadata;
import com.huaweicloud.dis.adapter.kafka.common.TopicPartition;
import org.apache.flink.annotation.Internal;
import org.apache.flink.metrics.MetricGroup;
import org.apache.flink.streaming.api.functions.AssignerWithPeriodicWatermarks;
import org.apache.flink.streaming.api.functions.AssignerWithPunctuatedWatermarks;
import org.apache.flink.streaming.api.functions.source.SourceFunction.SourceContext;
import org.apache.flink.streaming.runtime.tasks.ProcessingTimeService;
import org.apache.flink.streaming.util.serialization.KeyedDeserializationSchema;
import org.apache.flink.util.SerializedValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.apache.flink.util.Preconditions.checkState;

/**
 * A fetcher that fetches data from DIS.
 *
 * @param <T> The type of elements produced by the fetcher.
 */
@Internal
public class DisFetcher<T> extends AbstractFetcher<T, TopicPartition> {

	private static final Logger LOG = LoggerFactory.getLogger(DisFetcher.class);

	// ------------------------------------------------------------------------

	/** The schema to convert between Kafka's byte messages, and Flink's objects. */
	private final KeyedDeserializationSchema<T> deserializer;

	/** The handover of data and exceptions between the consumer thread and the task thread. */
	private final Handover handover;

	/** The thread that runs the actual KafkaConsumer and hand the record batches to this fetcher. */
	private final DisConsumerThread consumerThread;

	/** Flag to mark the main work loop as alive. */
	private volatile boolean running = true;

	// ------------------------------------------------------------------------

	public DisFetcher(
			SourceContext<T> sourceContext,
			Map<DisStreamPartition, Long> assignedPartitionsWithInitialOffsets,
			SerializedValue<AssignerWithPeriodicWatermarks<T>> watermarksPeriodic,
			SerializedValue<AssignerWithPunctuatedWatermarks<T>> watermarksPunctuated,
			ProcessingTimeService processingTimeProvider,
			long autoWatermarkInterval,
			ClassLoader userCodeClassLoader,
			String taskNameWithSubtasks,
			KeyedDeserializationSchema<T> deserializer,
			Properties kafkaProperties,
			long pollTimeout,
			MetricGroup subtaskMetricGroup,
			MetricGroup consumerMetricGroup,
			boolean useMetrics) throws Exception {
		super(
				sourceContext,
				assignedPartitionsWithInitialOffsets,
				watermarksPeriodic,
				watermarksPunctuated,
				processingTimeProvider,
				autoWatermarkInterval,
				userCodeClassLoader,
				consumerMetricGroup,
				useMetrics);

		this.deserializer = deserializer;
		this.handover = new Handover();

		this.consumerThread = new DisConsumerThread(
				LOG,
				handover,
				kafkaProperties,
				unassignedPartitionsQueue,
                unrevokedPartitionsQueue,
				createCallBridge(),
				getFetcherName() + " for " + taskNameWithSubtasks,
				pollTimeout,
				useMetrics,
				consumerMetricGroup,
				subtaskMetricGroup);
	}

	// ------------------------------------------------------------------------
	//  Fetcher work methods
	// ------------------------------------------------------------------------

	@Override
	public void runFetchLoop() throws Exception {
		try {
			final Handover handover = this.handover;

			// kick off the actual Kafka consumer
			consumerThread.start();

			while (running) {
				// this blocks until we get the next records
				// it automatically re-throws exceptions encountered in the consumer thread
				final ConsumerRecords<byte[], byte[]> records = handover.pollNext();

				// get the records for each topic partition
				for (DisStreamPartitionState<TopicPartition> partition : subscribedPartitionStates()) {

					List<ConsumerRecord<byte[], byte[]>> partitionRecords =
							records.records(partition.getKafkaPartitionHandle());

					for (ConsumerRecord<byte[], byte[]> record : partitionRecords) {
						final T value = deserializer.deserialize(
								record.key(), record.value(),
								record.topic(), record.partition(), record.offset());

						if (deserializer.isEndOfStream(value)) {
							// end of stream signaled
							running = false;
							break;
						}

						// emit the actual record. this also updates offset state atomically
						// and deals with timestamps and watermark generation
						emitRecord(value, partition, record.offset(), record);
					}
				}
			}
		}
		finally {
			// this signals the consumer thread that no more work is to be done
			consumerThread.shutdown();
		}

		// on a clean exit, wait for the runner thread
		try {
			consumerThread.join();
		}
		catch (InterruptedException e) {
			// may be the result of a wake-up interruption after an exception.
			// we ignore this here and only restore the interruption state
			Thread.currentThread().interrupt();
		}
	}

	@Override
	public void cancel() {
		// flag the main thread to exit. A thread interrupt will come anyways.
		running = false;
		handover.close();
		consumerThread.shutdown();
	}

	// ------------------------------------------------------------------------
	//  The below methods are overridden in the 0.10 fetcher, which otherwise
	//   reuses most of the 0.9 fetcher behavior
	// ------------------------------------------------------------------------

	protected void emitRecord(
			T record,
			DisStreamPartitionState<TopicPartition> partition,
			long offset,
			@SuppressWarnings("UnusedParameters") ConsumerRecord<?, ?> consumerRecord) throws Exception {

		// the 0.9 Fetcher does not try to extract a timestamp
        emitRecordWithTimestamp(record, partition, offset, consumerRecord.timestamp());
	}

	/**
	 * Gets the name of this fetcher, for thread naming and logging purposes.
	 */
	protected String getFetcherName() {
		return "Dis Fetcher";
	}

	protected DisConsumerCallBridge createCallBridge() {
		return new DisConsumerCallBridge();
	}

	// ------------------------------------------------------------------------
	//  Implement Methods of the AbstractFetcher
	// ------------------------------------------------------------------------

	@Override
	public TopicPartition createKafkaPartitionHandle(DisStreamPartition partition) {
		return new TopicPartition(partition.getTopic(), partition.getPartition());
	}

	@Override
	protected void doCommitInternalOffsetsToDis(
			Map<DisStreamPartition, Long> offsets,
			@Nonnull DisCommitCallback commitCallback) throws Exception {

		@SuppressWarnings("unchecked")
		List<DisStreamPartitionState<TopicPartition>> partitions = subscribedPartitionStates();

		Map<TopicPartition, OffsetAndMetadata> offsetsToCommit = new HashMap<>(partitions.size());

		for (DisStreamPartitionState<TopicPartition> partition : partitions) {
			Long lastProcessedOffset = offsets.get(partition.getKafkaTopicPartition());
			if (lastProcessedOffset != null) {
				checkState(lastProcessedOffset >= 0, "Illegal offset value to commit");

				// committed offsets through the KafkaConsumer need to be 1 more than the last processed offset.
				// This does not affect Flink's checkpoints/saved state.
				long offsetToCommit = lastProcessedOffset + 1;

				offsetsToCommit.put(partition.getKafkaPartitionHandle(), new OffsetAndMetadata(offsetToCommit));
				partition.setCommittedOffset(offsetToCommit);
			}
		}

		// record the work to be committed by the main consumer thread and make sure the consumer notices that
		consumerThread.setOffsetsToCommit(offsetsToCommit, commitCallback);
	}
}
