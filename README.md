## IB TWS API Historical Data Download

Codes to download US single stock historical data using IB TWS API.  

#### Needs
- [Interactive Brokers TWS API](https://ibkrcampus.com/ibkr-api-page/twsapi-doc/#find-the-api)
- JDK

#### Inputs
- Stock ticker
- Data interval/granularity (from 1min to 1d)
- End date of data window ("" for today)
- Data window (how long )
- Output file path 
- Output file format: csv (default) or json

#### Outputs
- Trade, Bid, Ask prices in CSV or JSON


#### API Limitations and Workarounds
- Because request data types are seperated into bid, ask, and traded prices and only one can be sent per request, requests have to be repeatedly sent for both bids and asks. The seperate data are joined after checking for identical timestamps.
- IB emphasizes it is not a data provider and limits return data points to a few hundreds per request (soft limit), so data windows are directly tied to granularity/interval requested; for 1-min data, 390 (6.5hrs x 60mins) points per day, so 2 days window (780points) per request appropriate. Codes have to slice the requested window, send separate requests, and combine the output. 
- Maximum 10 simultaneous requests.
