/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.beam.integration.nexmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.hash.Hashing;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.List;

import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.coders.AvroCoder;
import org.apache.beam.sdk.coders.ByteArrayCoder;
import org.apache.beam.sdk.coders.Coder;
import org.apache.beam.sdk.coders.CoderException;
import org.apache.beam.sdk.coders.CoderRegistry;
import org.apache.beam.sdk.coders.CustomCoder;
import org.apache.beam.sdk.coders.SerializableCoder;
import org.apache.beam.sdk.io.Read;
import org.apache.beam.sdk.transforms.Aggregator;
import org.apache.beam.sdk.transforms.Combine;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.PTransform;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.transforms.Sum;
import org.apache.beam.sdk.transforms.windowing.AfterPane;
import org.apache.beam.sdk.transforms.windowing.BoundedWindow;
import org.apache.beam.sdk.transforms.windowing.GlobalWindows;
import org.apache.beam.sdk.transforms.windowing.Window;
import org.apache.beam.sdk.util.state.StateSpec;
import org.apache.beam.sdk.util.state.StateSpecs;
import org.apache.beam.sdk.util.state.ValueState;
import org.apache.beam.sdk.values.PBegin;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.TimestampedValue;
import org.joda.time.Duration;
import org.joda.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Odd's 'n Ends used throughout queries and driver.
 */
public class NexmarkUtils {
  private static final Logger LOG = LoggerFactory.getLogger(NexmarkUtils.class);

  /**
   * Mapper for (de)serializing JSON.
   */
  static final ObjectMapper MAPPER = new ObjectMapper();

  /**
   * Possible sources for events.
   */
  public enum SourceType {
    /**
     * Produce events directly.
     */
    DIRECT,
    /**
     * Read events from an Avro file.
     */
    AVRO,
    /**
     * Read from a PubSub topic. It will be fed the same synthetic events by this pipeline.
     */
    PUBSUB
  }

  /**
   * Possible sinks for query results.
   */
  public enum SinkType {
    /**
     * Discard all results.
     */
    COUNT_ONLY,
    /**
     * Discard all results after converting them to strings.
     */
    DEVNULL,
    /**
     * Write to a PubSub topic. It will be drained by this pipeline.
     */
    PUBSUB,
    /**
     * Write to a text file. Only works in batch mode.
     */
    TEXT,
    /**
     * Write raw Events to Avro. Only works in batch mode.
     */
    AVRO,
    /**
     * Write raw Events to BigQuery.
     */
    BIGQUERY,
  }

  /**
   * Pub/sub mode to run in.
   */
  public enum PubSubMode {
    /**
     * Publish events to pub/sub, but don't run the query.
     */
    PUBLISH_ONLY,
    /**
     * Consume events from pub/sub and run the query, but don't publish.
     */
    SUBSCRIBE_ONLY,
    /**
     * Both publish and consume, but as separate jobs.
     */
    COMBINED
  }

  /**
   * Coder strategies.
   */
  public enum CoderStrategy {
    /**
     * Hand-written.
     */
    HAND,
    /**
     * Avro.
     */
    AVRO,
    /**
     * Java serialization.
     */
    JAVA
  }

  /**
   * How to determine resource names.
   */
  public enum ResourceNameMode {
    /** Names are used as provided. */
    VERBATIM,
    /** Names are suffixed with the query being run. */
    QUERY,
    /** Names are suffixed with the query being run and a random number. */
    QUERY_AND_SALT;
  }

  /**
   * Units for rates.
   */
  public enum RateUnit {
    PER_SECOND(1_000_000L),
    PER_MINUTE(60_000_000L);

    RateUnit(long usPerUnit) {
      this.usPerUnit = usPerUnit;
    }

    /**
     * Number of microseconds per unit.
     */
    private final long usPerUnit;

    /**
     * Number of microseconds between events at given rate.
     */
    public long rateToPeriodUs(long rate) {
      return (usPerUnit + rate / 2) / rate;
    }
  }

  /**
   * Shape of event rate.
   */
  public enum RateShape {
    SQUARE,
    SINE;

    /**
     * Number of steps used to approximate sine wave.
     */
    private static final int N = 10;

    /**
     * Return inter-event delay, in microseconds, for each generator
     * to follow in order to achieve {@code rate} at {@code unit} using {@code numGenerators}.
     */
    public long interEventDelayUs(int rate, RateUnit unit, int numGenerators) {
      return unit.rateToPeriodUs(rate) * numGenerators;
    }

    /**
     * Return array of successive inter-event delays, in microseconds, for each generator
     * to follow in order to achieve this shape with {@code firstRate/nextRate} at
     * {@code unit} using {@code numGenerators}.
     */
    public long[] interEventDelayUs(
        int firstRate, int nextRate, RateUnit unit, int numGenerators) {
      if (firstRate == nextRate) {
        long[] interEventDelayUs = new long[1];
        interEventDelayUs[0] = unit.rateToPeriodUs(firstRate) * numGenerators;
        return interEventDelayUs;
      }

      switch (this) {
        case SQUARE: {
          long[] interEventDelayUs = new long[2];
          interEventDelayUs[0] = unit.rateToPeriodUs(firstRate) * numGenerators;
          interEventDelayUs[1] = unit.rateToPeriodUs(nextRate) * numGenerators;
          return interEventDelayUs;
        }
        case SINE: {
          double mid = (firstRate + nextRate) / 2.0;
          double amp = (firstRate - nextRate) / 2.0; // may be -ve
          long[] interEventDelayUs = new long[N];
          for (int i = 0; i < N; i++) {
            double r = (2.0 * Math.PI * i) / N;
            double rate = mid + amp * Math.cos(r);
            interEventDelayUs[i] = unit.rateToPeriodUs(Math.round(rate)) * numGenerators;
          }
          return interEventDelayUs;
        }
      }
      throw new RuntimeException(); // switch should be exhaustive
    }

    /**
     * Return delay between steps, in seconds, for result of {@link #interEventDelayUs}, so
     * as to cycle through the entire sequence every {@code ratePeriodSec}.
     */
    public int stepLengthSec(int ratePeriodSec) {
      int n = 0;
      switch (this) {
        case SQUARE:
          n = 2;
          break;
        case SINE:
          n = N;
          break;
      }
      return (ratePeriodSec + n - 1) / n;
    }
  }

  /**
   * Set to true to capture all info messages. The logging level flags don't currently work.
   */
  private static final boolean LOG_INFO = false;

  /**
   * Set to true to capture all error messages. The logging level flags don't currently work.
   */
  private static final boolean LOG_ERROR = true;

  /**
   * Set to true to log directly to stdout on VM. You can watch the results in real-time with:
   * tail -f /var/log/dataflow/streaming-harness/harness-stdout.log
   */
  private static final boolean LOG_TO_CONSOLE = false;

  /**
   * Log info message.
   */
  public static void info(String format, Object... args) {
    if (LOG_INFO) {
      LOG.info(String.format(format, args));
      if (LOG_TO_CONSOLE) {
        System.out.println(String.format(format, args));
      }
    }
  }

  /**
   * Log message to console. For client side only.
   */
  public static void console(String format, Object... args) {
    System.out.printf("%s %s\n", Instant.now(), String.format(format, args));
  }

  /**
   * Label to use for timestamps on pub/sub messages.
   */
  public static final String PUBSUB_TIMESTAMP = "timestamp";

  /**
   * Label to use for windmill ids on pub/sub messages.
   */
  public static final String PUBSUB_ID = "id";

  /**
   * All events will be given a timestamp relative to this time (ms since epoch).
   */
  public static final long BASE_TIME = Instant.parse("2015-07-15T00:00:00.000Z").getMillis();

  /**
   * Instants guaranteed to be strictly before and after all event timestamps, and which won't
   * be subject to underflow/overflow.
   */
  public static final Instant BEGINNING_OF_TIME = new Instant(0).plus(Duration.standardDays(365));
  public static final Instant END_OF_TIME =
      BoundedWindow.TIMESTAMP_MAX_VALUE.minus(Duration.standardDays(365));

  /**
   * Setup pipeline with codes and some other options.
   */
  public static void setupPipeline(CoderStrategy coderStrategy, Pipeline p) {
    //TODO Ismael check
//    PipelineRunner<?> runner = p.getRunner();
//    if (runner instanceof DirectRunner) {
//      // Disable randomization of output since we want to check batch and streaming match the
//      // model both locally and on the cloud.
//      ((DirectRunner) runner).withUnorderednessTesting(false);
//    }

    CoderRegistry registry = p.getCoderRegistry();
    switch (coderStrategy) {
      case HAND:
        registry.registerCoder(Auction.class, Auction.CODER);
        registry.registerCoder(AuctionBid.class, AuctionBid.CODER);
        registry.registerCoder(AuctionCount.class, AuctionCount.CODER);
        registry.registerCoder(AuctionPrice.class, AuctionPrice.CODER);
        registry.registerCoder(Bid.class, Bid.CODER);
        registry.registerCoder(CategoryPrice.class, CategoryPrice.CODER);
        registry.registerCoder(Event.class, Event.CODER);
        registry.registerCoder(IdNameReserve.class, IdNameReserve.CODER);
        registry.registerCoder(NameCityStateId.class, NameCityStateId.CODER);
        registry.registerCoder(Person.class, Person.CODER);
        registry.registerCoder(SellerPrice.class, SellerPrice.CODER);
        registry.registerCoder(Done.class, Done.CODER);
        registry.registerCoder(BidsPerSession.class, BidsPerSession.CODER);
        break;
      case AVRO:
        registry.setFallbackCoderProvider(AvroCoder.PROVIDER);
        break;
      case JAVA:
        registry.setFallbackCoderProvider(SerializableCoder.PROVIDER);
        break;
    }
  }

  /**
   * Return a generator config to match the given {@code options}.
   */
  public static GeneratorConfig standardGeneratorConfig(NexmarkConfiguration configuration) {
    return new GeneratorConfig(configuration,
                               configuration.useWallclockEventTime ? System.currentTimeMillis()
                                                                   : BASE_TIME, 0,
                               configuration.numEvents, 0);
  }

  /**
   * Return an iterator of events using the 'standard' generator config.
   */
  public static Iterator<TimestampedValue<Event>> standardEventIterator(
      NexmarkConfiguration configuration) {
    return new Generator(standardGeneratorConfig(configuration));
  }

  /**
   * Return a transform which yields a finite number of synthesized events generated
   * as a batch.
   */
  public static PTransform<PBegin, PCollection<Event>> batchEventsSource(
          NexmarkConfiguration configuration) {
    return Read.from(new BoundedEventSource(
            NexmarkUtils.standardGeneratorConfig(configuration), configuration.numEventGenerators));
  }

  /**
   * Return a transform which yields a finite number of synthesized events generated
   * on-the-fly in real time.
   */
  public static PTransform<PBegin, PCollection<Event>> streamEventsSource(
          NexmarkConfiguration configuration) {
    return Read.from(new UnboundedEventSource(NexmarkUtils.standardGeneratorConfig(configuration),
                                              configuration.numEventGenerators,
                                              configuration.watermarkHoldbackSec,
                                              configuration.isRateLimited));
  }

  /**
   * Return a transform to pass-through events, but count them as they go by.
   */
  public static ParDo.Bound<Event, Event> snoop(final String name) {
    return ParDo.of(new DoFn<Event, Event>() {
                  final Aggregator<Long, Long> eventCounter =
                      createAggregator("events", Sum.ofLongs());
                  final Aggregator<Long, Long> newPersonCounter =
                      createAggregator("newPersons", Sum.ofLongs());
                  final Aggregator<Long, Long> newAuctionCounter =
                      createAggregator("newAuctions", Sum.ofLongs());
                  final Aggregator<Long, Long> bidCounter =
                      createAggregator("bids", Sum.ofLongs());
                  final Aggregator<Long, Long> endOfStreamCounter =
                      createAggregator("endOfStream", Sum.ofLongs());

                  @ProcessElement
                  public void processElement(ProcessContext c) {
                    eventCounter.addValue(1L);
                    if (c.element().newPerson != null) {
                      newPersonCounter.addValue(1L);
                    } else if (c.element().newAuction != null) {
                      newAuctionCounter.addValue(1L);
                    } else if (c.element().bid != null) {
                      bidCounter.addValue(1L);
                    } else {
                      endOfStreamCounter.addValue(1L);
                    }
                    info("%s snooping element %s", name, c.element());
                    c.output(c.element());
                  }
                });
  }

  /**
   * Return a transform to count and discard each element.
   */
  public static <T> ParDo.Bound<T, Void> devNull(String name) {
    return ParDo.of(new DoFn<T, Void>() {
                  final Aggregator<Long, Long> discardCounter =
                      createAggregator("discarded", Sum.ofLongs());

                  @ProcessElement
                  public void processElement(ProcessContext c) {
                    discardCounter.addValue(1L);
                  }
                });
  }

  /**
   * Return a transform to log each element, passing it through unchanged.
   */
  public static <T> ParDo.Bound<T, T> log(final String name) {
    return ParDo.of(new DoFn<T, T>() {
                  @ProcessElement
                  public void processElement(ProcessContext c) {
                    LOG.info("%s: %s", name, c.element());
                    c.output(c.element());
                  }
                });
  }

  /**
   * Return a transform to format each element as a string.
   */
  public static <T> ParDo.Bound<T, String> format(String name) {
    return ParDo.of(new DoFn<T, String>() {
                  final Aggregator<Long, Long> recordCounter =
                      createAggregator("records", Sum.ofLongs());

                  @ProcessElement
                  public void processElement(ProcessContext c) {
                    recordCounter.addValue(1L);
                    c.output(c.element().toString());
                  }
                });
  }

  /**
   * Return a transform to make explicit the timestamp of each element.
   */
  public static <T> ParDo.Bound<T, TimestampedValue<T>> stamp(String name) {
    return ParDo.of(new DoFn<T, TimestampedValue<T>>() {
                  @ProcessElement
                  public void processElement(ProcessContext c) {
                    c.output(TimestampedValue.of(c.element(), c.timestamp()));
                  }
                });
  }

  /**
   * Return a transform to reduce a stream to a single, order-invariant long hash.
   */
  public static <T> PTransform<PCollection<T>, PCollection<Long>> hash(
      final long numEvents, String name) {
    return new PTransform<PCollection<T>, PCollection<Long>>(name) {
      @Override
      public PCollection<Long> expand(PCollection<T> input) {
        return input.apply(Window.<T>into(new GlobalWindows())
                               .triggering(AfterPane.elementCountAtLeast((int) numEvents))
                               .withAllowedLateness(Duration.standardDays(1))
                               .discardingFiredPanes())

                    .apply(name + ".Hash", ParDo.of(new DoFn<T, Long>() {
                      @ProcessElement
                      public void processElement(ProcessContext c) {
                        long hash =
                            Hashing.murmur3_128()
                                   .newHasher()
                                   .putLong(c.timestamp().getMillis())
                                   .putString(c.element().toString(), StandardCharsets.UTF_8)
                                   .hash()
                                   .asLong();
                        c.output(hash);
                      }
                    }))

                    .apply(Combine.globally(new Combine.BinaryCombineFn<Long>() {
                      @Override
                      public Long apply(Long left, Long right) {
                        return left ^ right;
                      }
                    }));
      }
    };
  }

  private static final long MASK = (1L << 16) - 1L;
  private static final long HASH = 0x243F6A8885A308D3L;
  private static final long INIT_PLAINTEXT = 50000L;

  /**
   * Return a transform to keep the CPU busy for given milliseconds on every record.
   */
  public static <T> ParDo.Bound<T, T> cpuDelay(String name, final long delayMs) {
    return ParDo.of(new DoFn<T, T>() {
                  @ProcessElement
                  public void processElement(ProcessContext c) {
                    long now = System.currentTimeMillis();
                    long end = now + delayMs;
                    while (now < end) {
                      // Find plaintext which hashes to HASH in lowest MASK bits.
                      // Values chosen to roughly take 1ms on typical workstation.
                      long p = INIT_PLAINTEXT;
                      while (true) {
                        long t = Hashing.murmur3_128().hashLong(p).asLong();
                        if ((t & MASK) == (HASH & MASK)) {
                          break;
                        }
                        p++;
                      }
                      long next = System.currentTimeMillis();
                      now = next;
                    }
                    c.output(c.element());
                  }
                });
  }

  private static final StateSpec<Object, ValueState<byte[]>> DUMMY_TAG =
          StateSpecs.value(ByteArrayCoder.of());
  private static final int MAX_BUFFER_SIZE = 1 << 24;

  /**
   * Return a transform to write given number of bytes to durable store on every record.
   */
  public static <T> ParDo.Bound<T, T> diskBusy(String name, final long bytes) {
    return ParDo.of(new DoFn<T, T>() {
                  @ProcessElement
                  public void processElement(ProcessContext c) {
                    long remain = bytes;
                    long start = System.currentTimeMillis();
                    long now = start;
                    while (remain > 0) {
                      long thisBytes = Math.min(remain, MAX_BUFFER_SIZE);
                      remain -= thisBytes;
                      byte[] arr = new byte[(int) thisBytes];
                      for (int i = 0; i < thisBytes; i++) {
                        arr[i] = (byte) now;
                      }
                      //TODO Ismael google on state
//                      ValueState<byte[]> state = c.windowingInternals().stateInternals().state(
//                          StateNamespaces.global(), DUMMY_TAG);
//                      state.write(arr);
                      now = System.currentTimeMillis();
                    }
                    c.output(c.element());
                  }
                });
  }

  /**
   * Return a transform to cast each element to {@link KnownSize}.
   */
  private static <T extends KnownSize> ParDo.Bound<T, KnownSize> castToKnownSize() {
    return ParDo.of(new DoFn<T, KnownSize>() {
                  @ProcessElement
                  public void processElement(ProcessContext c) {
                    c.output(c.element());
                  }
                });
  }

  /**
   * A coder for instances of {@code T} cast up to {@link KnownSize}.
   *
   * @param <T> True type of object.
   */
  private static class CastingCoder<T extends KnownSize> extends CustomCoder<KnownSize> {
    private final Coder<T> trueCoder;

    public CastingCoder(Coder<T> trueCoder) {
      this.trueCoder = trueCoder;
    }

    @Override
    public void encode(KnownSize value, OutputStream outStream, Context context)
        throws CoderException, IOException {
      @SuppressWarnings("unchecked")
      T typedValue = (T) value;
      trueCoder.encode(typedValue, outStream, context);
    }

    @Override
    public KnownSize decode(InputStream inStream, Context context)
        throws CoderException, IOException {
      return trueCoder.decode(inStream, context);
    }

    @Override
    public List<? extends Coder<?>> getComponents() {
      return ImmutableList.of(trueCoder);
    }
  }

  /**
   * Return a coder for {@code KnownSize} that are known to be exactly of type {@code T}.
   */
  private static <T extends KnownSize> Coder<KnownSize> makeCastingCoder(Coder<T> trueCoder) {
    return new CastingCoder<>(trueCoder);
  }

  /**
   * Return {@code elements} as {@code KnownSize}s.
   */
  public static <T extends KnownSize> PCollection<KnownSize> castToKnownSize(
      final String name, PCollection<T> elements) {
    return elements.apply(name + ".Forget", castToKnownSize())
            .setCoder(makeCastingCoder(elements.getCoder()));
  }

  // Do not instantiate.
  private NexmarkUtils() {
  }
}
