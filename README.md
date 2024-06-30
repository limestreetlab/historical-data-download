## IB TWS API Historical Data Download

Codes to download <u>US single stock</u> historical data using IB TWS API.  

#### Needs
- [Interactive Brokers TWS API](https://ibkrcampus.com/ibkr-api-page/twsapi-doc/#find-the-api)
- Java8+

#### How to Use


#### Input parameters
- Stock ticker
- Data interval/granularity (from 1min to 1d)
- End date of data window ("" for today)
- Data period (how long to retrieve)
- Output file path 

#### Outputs
- CSV data, in chronological order (old data first)
- Intraday data are timestamp (yyyyMMdd HH::mm:ss), bid, ask, open, high, low, close, volume
- Interday data are timestamp (yyyyMMdd), open, high, low, close, vwap, volume

#### Comments
- All times are defaulted to EST America/New York, 9:30 to 15:59
- Bid and ask prices are open prices at timestamps
- Traded prices represent first traded prices in the period; for example, in case of 1-min data, the traded at 14:50:00 is the first price traded at between 14:50:00 and 14:51:00 and volume is for that minute
- Trading volumes provided by IBKR are lower than other sources (Yahoo Finance etc), often by a substantial margin. [IBKR data feed filters trades](https://ibkrcampus.com/ibkr-api-page/twsapi-doc/#filtered-hist-data) that tend to occur away from NBBO such as block trades

#### API Limitations and Workarounds
- Because request data types are seperated into bid, ask, and traded prices and only one can be sent per request, requests have to be repeatedly sent. The seperate data are joined after checking for timestamps.
- IB emphasizes it is not a data provider and limits return data points to a few hundreds per request (soft limit), so data windows are directly tied to granularity/interval requested; for 1-min data, 390 (6.5hrs x 60mins) points per day, so 2 days window (780points) per request appropriate. Codes have to slice the requested window, send separate requests, and combine the output. 
- Maximum 10 simultaneous requests.

#### Logic Overview
- One class (singleton), whose instance used to connect to TWS and perform all requests using different request identifiers (reqId)
- EReader listens to incoming messages and pushes all messages into the queue
- Built-in `EReader.processMsgs()` then called to pass received data and tagged reqId to relevant callback `HistoricalData()`
- `HistoricalData` is called repeatedly for every message (data point) in a request, related callback `HistoricalDataEnd()` is called when all messages of a request are sent
- Flag `isDone` used to track if all messages of a request are received, if so break from message listening loop
- Stay in listening loop until all request flags signal completion
- Accumulate messages into a queue (LinkedList) for each request
- Combine data points in queues after checking/matching timestamps, output combined result

#### Future Works
- Expand to non-equity contracts, especially FX and futures
