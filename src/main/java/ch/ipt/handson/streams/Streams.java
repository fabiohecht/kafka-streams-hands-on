package ch.ipt.handson.streams;

import ch.ipt.handson.event.Book;
import ch.ipt.handson.event.WebsiteInteraction;
import io.confluent.kafka.serializers.AbstractKafkaAvroSerDeConfig;
import io.confluent.kafka.streams.serdes.avro.SpecificAvroSerde;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.common.protocol.types.Field;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.*;
import org.apache.kafka.streams.kstream.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.Properties;


public class Streams {
    static final Logger log = LoggerFactory.getLogger(Streams.class);

    private static final String INPUT_TOPIC_INTERACTION = "interaction";
    private static final String OUTPUT_TOPIC_TOP_VIEWED = "most-viewed";

    private static final String INPUT_TOPIC_ORDER = "order";

    private static Properties config = new Properties();

    static public void main(String[] args) {
        initializeConfig();
        startStream();
    }

    static void initializeConfig() {

        config.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
        config.put(AbstractKafkaAvroSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG, "http://localhost:8081");
        config.put(StreamsConfig.STATE_DIR_CONFIG, "/tmp/kafka-streams");

        config.put(StreamsConfig.APPLICATION_ID_CONFIG, "FHE-streams");
        config.put(StreamsConfig.CLIENT_ID_CONFIG, "Streams-v1");
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        config.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.String().getClass().getName());
        config.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, SpecificAvroSerde.class);

        // Enable record cache of size 10 MB.
        config.put(StreamsConfig.CACHE_MAX_BYTES_BUFFERING_CONFIG, 10 * 1024 * 1024L);

        // Set commit interval to 1 second
        config.put(StreamsConfig.COMMIT_INTERVAL_MS_CONFIG, 1000);
    }

    private static void startStream() {

        log.info("starting kafka streams");

        final StreamsBuilder builder = new StreamsBuilder();

        final KStream<String, WebsiteInteraction> interactionKStream = builder.stream(INPUT_TOPIC_INTERACTION);
        Serde<String> stringSerde = Serdes.String();
        //final KTable<String, Book> employeeTable = builder.table(INPUT_TOPIC_EMPLOYEE, Materialized.with(stringSerde, stringSerde));

        final Serde<Long> longSerde = Serdes.Long();

        final Serde<Book> bookSerde = new SpecificAvroSerde<>();
        // Note how we must manually call `configure()` on this serde to configure the schema registry
        // url.  This is different from the case of setting default serdes (see `streamsConfiguration`
        // above), which will be auto-configured based on the `StreamsConfiguration` instance.
        final boolean isKeySerde = false;
        bookSerde.configure(
                Collections.singletonMap(AbstractKafkaAvroSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG, "http://localhost:8081"),
                isKeySerde);

        KStream<Book, Integer> newKeyed =
                interactionKStream
                        .filter((key, value) -> value.getEvent().equals("view"))
                        // for demo/debugging purposes, output what has come in through the KStream (does not change stream)
                        .peek((k, v) -> log.debug(" new interaction: {}", v.toString()))
                        //.map((s, interaction) -> new KeyValue<>(interaction.getBook().getTitle(), 1L))

                        // aggregations are done with groupBy then reduce or aggregate
                        //.selectKey((key, value) -> value.getBook().getTitle())
                        //        .mapValues(value -> 1)
                        //        .groupByKey(Serialized.with(SpecificAvroSerde.class, Serdes.Integer()))
                        .map((key, value) -> KeyValue.pair(value.getBook(), new Integer(1)))
                        .peek((k, v) -> log.debug(" mapped: {} {}", k, v));


        KGroupedStream<Book, Integer> grouped = newKeyed
                .groupByKey(
                        Serialized.with(bookSerde, Serdes.Integer())
                );

        KTable<Book, Integer> bookCounts = grouped.reduce((value1, value2) -> value1 + value2);

        bookCounts.toStream()
                .peek((k, v) -> log.debug(" counted: {} {}", k, v));

        bookCounts.toStream().to("test-6", Produced.with(bookSerde, Serdes.Integer()));

//                        .reduce((v1, v2) -> {
//                            v1.getBook().setTitle(v1.getBook().getTitle() + v2.getBook().getTitle());
//                            return v1;
//                        }
//
//                        // converts values from Expense to SumValue
//                        .mapValues(expense ->
//                                SumValue.newBuilder()
//                                        .setEmployeeAcronym(expense.getEmployeeAcronym())
//                                        .setSumAmount(expense.getAmount())
//                                        .build()
//                        )
//
//                        //joins with employee to add full name
//                        //joins are always based on Keys, in our case we are lucky that both are keyed by acronym
//                        .join(
//                                employeeTable,
//                                (expense, name) -> {
//                                    expense.setEmployeeFullName(name);
//                                    return expense;
//                                });

        // for demo/debugging purposes, output what we are writing to the KTable
//        bookCounts
//                .toStream()
//                .peek((k, v) -> log.debug(" top10: {} {}", k, v.toString()))
//
//                // output KTable to topic
//                .to(OUTPUT_TOPIC_TOP_VIEWED);

        // starts stream
        final KafkaStreams streamsContracts = new KafkaStreams(builder.build(), config);

        // Java 8+, using lambda expressions
        streamsContracts.setUncaughtExceptionHandler((Thread thread, Throwable throwable) -> {
            // here you should examine the throwable/exception and perform an appropriate action!
            throwable.printStackTrace();
        });

        streamsContracts.cleanUp();
        streamsContracts.start();

        // Add shutdown hook to respond to SIGTERM and gracefully close Kafka Streams
        Runtime.getRuntime().addShutdownHook(new Thread(streamsContracts::close));
    }
}