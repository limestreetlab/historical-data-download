## IB TWS API Historical Data Download

Codes to download **US single stock** historical data using IB TWS API.  

#### Needs
- [Interactive Brokers TWS API](https://ibkrcampus.com/ibkr-api-page/twsapi-doc/#find-the-api), (source/JavaClient/com)
- Java8+

#### How to Use
- Run it in command line `java HistoricalDataDownloader` and provide inputs
- Data will be saved in provided directory
- If used from another class, call static `HistoricalDataDownloader::getDownloader` and `HistoricalDataDownloader::start`

#### Input parameters
- Stock ticker
- Data interval/granularity (from 1 second to 1 week)
- End date of data window (year, month, day)
- Data period (how long to retrieve)
- Path to file output directory

#### Outputs
- CSV data, in chronological order (old data first)
- Intraday data are timestamp (yyyyMMdd HH::mm:ss), bid, ask, open, high, low, close, volume
- Interday data are timestamp (yyyyMMdd), open, high, low, close, volume
- OHLC are of traded prices

#### Comments
- All times are defaulted to EST America/New York, 9:30 to 15:59, regular trading hours
- Bid and ask prices are open prices at timestamps
- For intraday data, the close of the last data point (ex 15:59:00 for 1-min) is different from the daily close which results from closing auction
- Traded prices represent first traded prices in the period; for example, in case of 1-min data, the traded at 14:50:00 is the first price traded at between 14:50:00 and 14:51:00 and volume is for that minute
- Trading volumes provided by IBKR are lower than other sources (Yahoo Finance etc), often by a substantial margin. Only RTH data are used here. [IBKR data feed filters trades](https://ibkrcampus.com/ibkr-api-page/twsapi-doc/#filtered-hist-data) that tend to occur away from NBBO such as block trades and excludes odd lot trades
- When a non-trading date entered, the last trading day will be used

#### API Limitations and Workarounds
- Because request data types are seperated into bid, ask, and traded prices and only one can be sent per request, requests have to be repeatedly sent. The seperate data are joined after checking for timestamps.
- IB emphasizes it is not a data provider and limits return data points to a few hundreds per request (soft limit), so data windows are directly tied to granularity/interval requested; for 1-min data, 390 (6.5hrs x 60mins) points per day, so 2 days window (780points) per request appropriate. Codes have to slice the requested window, send separate requests, and combine the output. 
- Maximum 10 simultaneous requests.

#### Logic Overview
- One class (singleton), whose instance used to connect to TWS and perform all requests using different request identifiers (reqId)
- EReader instance, tied to the socket, listens to incoming messages and pushes all messages into the queue
- Built-in `EReader.processMsgs()` then called to pass received data and tagged reqId from the queue to relevant callback `HistoricalData()`
- `HistoricalData` is called repeatedly for every message (data point) in a request, related callback `HistoricalDataEnd()` is called when all messages of a request are sent
- Data are accumulated into a data structure inside `HistoricalData` callback
- Flag `isDone` used to track if all messages are received as signaled by `HistoricalDataEnd`, until then keep looping
- `isIntraday` flag for intraday or interday data request
- Because IBKR bid, ask, and trades data require one request each, intraday data need to send 3 separate requests, of different (arbritarily assigned) ids, and results pushed into 3 containers to be combined into one at the end
- Interday data come only from TRADES request, so uses only one container
- Custom data types Bid, Ask, Trades defined, with compareTo and toString overriden
- IB data feed is chronological, so synchronous saving of custom data is in natural order already; but Comparable<Trades> makes possible to sort Trades type based on datetime

#### Future Works
- Expand to non-equity contracts, especially FX and futures
