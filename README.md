# tradr-importer

The importer fetches data from different sources (say, a broker) and publishes them to a kafka queue.
Right now, only data from one broker gets fetched and only one value (closing price) is pushed to the queue in order to simplify things.

