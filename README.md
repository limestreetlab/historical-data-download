## IB TWS API Historical Data Download

Codes to download **US single stock** historical data using IB TWS API.  

#### Needs
- [Interactive Brokers TWS API](https://ibkrcampus.com/ibkr-api-page/twsapi-doc/#find-the-api), <sub>(source/JavaClient/com)</sub>
- Java 15+

#### How to Use
- Run it in command line `java HistoricalDataDownloader` and input request parameters
- Data will be saved in provided directory, named ticker barSize yyyymmdd-yyyymmdd
- If used from another class, call static `HistoricalDataDownloader::getDownloader` and `HistoricalDataDownloader::start`

#### Input parameters
- Stock tickers, as String for one ticker or List\<String\> for multiple tickers
- End date of data window (year, month, day)
- Data period (how long to retrieve)
- Data interval/granularity (from 1 second to 1 week)
- Path to file output directory

#### Outputs
- CSV data, in chronological order (old data first)
- Intraday data: timestamp (yyyyMMdd HH::mm:ss), bid, ask, open, high, low, close, volume
- Interday data: timestamp (yyyyMMdd), open, high, low, close, volume
- OHLC are of traded prices
- null for data unavailable at a timestamp

#### Comments
- All times are defaulted to EST America/New York, 9:30 to 15:59, regular trading hours
- Bid and ask prices are open prices at timestamps
- For intraday data, the close of the last data point (like 15:59:00 for 1-min) is different from the daily close which results from closing auction
- Trading volumes provided by IBKR are lower than other sources (Yahoo Finance etc), often by a substantial margin. Only RTH data are used here. [IBKR data feed filters trades](https://ibkrcampus.com/ibkr-api-page/twsapi-doc/#filtered-hist-data) that tend to occur away from NBBO such as block trades and excludes odd lot trades
- When a non-trading date entered, it will be shifted to the last trading day and request period remains unchanged

#### API Limitations and Workarounds
- Because request data types are seperated into bid, ask, and traded prices and only one can be sent per request, requests have to be repeatedly submitted
- IB emphasizes it is not a data provider and limits return data points to a few hundreds per request (soft limit), so data windows are directly tied to granularity/interval requested; for 1-min data, 390 (6.5hrs x 60mins) data points per day, so 2-3 days window per request about appropriate
- Maximum 10 simultaneous requests
- Impossible to retrieve data for a stock prior to most recent corporate action. IB uses unique contract id (conid) to identify each contract. IB changes the conid upon stock splits and M&A. Request for stock data is tied to current conid, so pre-action data tied to old conid shown as non-existent. IB does not allow for querying old conids, limiting data retrieval window to life span of current conid. Only workaround is to save all conids prior to changes for later use. 

#### Logic Overview
- One class (singleton), whose instance used to connect to TWS and perform all requests using different request identifiers (reqId)
- Constructors overloaded to take ticker as either String or List\<String\>
- `start()` encapsulates major operations including TWS connect, ticker looping, contract setting, request, msg reading, saving, and disconnect
- EReader instance, tied to the socket, listens to incoming messages and pushes all messages into the queue
- Built-in `EReader.processMsgs()` then called to pass received data and tagged reqId from the queue to relevant callback `HistoricalData()`
- `HistoricalData` is called repeatedly for every message (data point) in a request, related callback `HistoricalDataEnd()` is called when all messages of a request are sent
- Data are accumulated into a collection (ArrayList or LinkedList) inside `HistoricalData` callback
- Flag `isDone` used to track if all messages are received as signaled by `HistoricalDataEnd`, until then keep looping
- `isIntraday` flag for intraday or interday data request
- Because IBKR bid, ask, and trades data require one request each, intraday data need to send 3 separate requests, of different (arbritarily assigned) ids, and results pushed into 3 collections to be combined into one at the end
- Interday data come only from TRADES request, so uses only one container
- Custom data types Bid, Ask, Trades defined, with compareTo and toString overriden
- IB data feed is chronological, so synchronous saving of custom data is in natural order already; but Comparable\<Trades\> makes possible to sort Trades type based on datetime

#### Future Works
- Request and save contract id into a separate file storing and tracking all contract ids
- Extend to non-equity contracts, especially FX and futures
