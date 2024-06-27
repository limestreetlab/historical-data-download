## IB TWS API Historical Data Download

Codes to download US single stock historical data using IB TWS API.  

#### Needs
- [Interactive Brokers TWS API](https://ibkrcampus.com/ibkr-api-page/twsapi-doc/#find-the-api)
- Java8+

#### Input parameters
- Stock ticker
- Data interval/granularity (from 1min to 1d)
- End date of data window ("" for today)
- Data window (duration to retrieve)
- Output file path 

#### Outputs
- Bid, Ask, Traded, Volume data in CSV, in reverse chronological order (newest date/time first)

#### Comments
- All times are defaulted to America/New York, 9:30 to 15:59
- Bid and ask prices are open prices at that timestamp
- Traded prices represent first traded prices in the period; If 14:50:00 and for 1-min data, then it is the first price traded between 14:50:00 and 14:51:00

#### API Limitations and Workarounds
- Because request data types are seperated into bid, ask, and traded prices and only one can be sent per request, requests have to be repeatedly sent. The seperate data are joined after checking for timestamps.
- IB emphasizes it is not a data provider and limits return data points to a few hundreds per request (soft limit), so data windows are directly tied to granularity/interval requested; for 1-min data, 390 (6.5hrs x 60mins) points per day, so 2 days window (780points) per request appropriate. Codes have to slice the requested window, send separate requests, and combine the output. 
- Maximum 10 simultaneous requests.

#### Logic Overview
- One class
- Using only one instance to connect to TWS and perform all requests using different request identifiers (reqId)
- EReader listens to incoming messages and pushes all messages into the queue
- Built-in `EReader.processMsgs()` then called to pass received data and tagged reqId to relevant callback `HistoricalData()`
- `HistoricalData` is called repeatedly for every message (data point) in a request, related callback `HistoricalDataEnd()` is called when all messages of a request are sent
- Flag `isDone` used to track if all messages of a request are received, if so break from message listening loop
- Stay in listening loop until all request flags signal completion
- Accumulate messages into a queue (LinkedList) for each request
- Combine data points in queues after checking/matching timestamps, output combined result
