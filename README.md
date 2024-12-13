## Unique IP Tracker

This is an application that consumes events from a Kafka Topic identifying unique IP addresses and offering an endpoint to query the current count of different identified addresses.

The received messages are validated and if a new IP addess is received it is stored in a Mongo DB collection, if the message is not valid no action is taken.

Finally an endpoint is exposed to obtain the count of the different IP addresses stored in the Mongo DB collection.

A `scala-cli` script is included for generating event messages for testing the application.

The application is formed by 4 main elements:

1. **Consumer**: A Kafka consumer which processes messages in the following format:

```
{
    "timestamp": "<timestamp in multiple formats>",
    "device_ip": "<IPv4 address>",
    "error_code": <numeric code>
}
```

The consumer processes the topic's messages in a partition based approach. This means an independent stream is executed to process each topic's partition messages.

2. **Timestamp Validator**: An utility to validate the received timestamp which can be in different formats and parse it into a UTC based timestamp.


3. **Repository**: Module that interacts with Mongo DB, contains the functions:

- Creating an index for optimizing queries over IP address field.
- Validating the existence of an IP address in the collection.
- Store a new one.
- Calculate the count of the stored IP addresses.

4. **Routes**: Exposes a `GET /count` endpoint which returns the current count of stored IP addresses.

### Usage

1. Create application's docker image:

```
$ cd unique-ip-tracker
$ sbt docker:publishLocal
```

2. Start application and its required infrastructure (it will start three instances of the tracker application):

```
$ cd unique-ip-tracker
$ docker compose up
```

3. Start events generator application to populate the topic with new events to be processed by the tracker application:

```
$ cd events-generator
$ scala-cli run EventsGenerator.scala
```

4. Verify the current stored IP address count (in any of the started tracker instances):

```
$ curl http://localhost:8080/count
$ curl http://localhost:8081/count
$ curl http://localhost:8082/count
```

### Tests

For executing the application's unit tests:

```
$ cd unique-ip-tracker
$ sbt clean compile
$ sbt test
```

### Scaling Considerations

The current implementation is supported on Mongo instead a in-memory alternative (ie. Redis) to an easier scaling.

If the load increases some actions can be taken:

- Split the tracker application in two: one exposing the count endpoint and other for consuming the messages. This will allow an easier tuning in the required instances of each application for supporting the required load.


- Create and scale clusters of Kafka and Mongo.


- Define a grouping mechanism for the messages (ie. by the IP address region), this grouping could allow:

  - configuring sharding for distributing the database records
  - tuning the kafka topic partitions (ie. one for region)
  - offering IP addresses counts by region and use a composed database index (region + ip address)  

### Troubleshooting

For diagnosis an verification the following commands could be useful:

1. Connect to kafka container:

```
$ docker exec -it kafka bash
```

2. Verify the available topics:

```
$ kafka-topics --bootstrap-server localhost:29092 --list
```

3. Verify the topic configuration:

```
$ kafka-topics --bootstrap-server localhost:29092 --topic ip_tracker_topic --describe
```

4. Verify the topic consumers and its assigned partitions:

```
$ kafka-consumer-groups --bootstrap-server localhost:9092 --group ip_tracker_group --describe
```
