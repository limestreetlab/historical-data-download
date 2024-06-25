import com.ib.client.*;
import java.util.stream.*;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.Callable;
import java.time.format.DateTimeFormatter;
import java.io.IOException;
import java.time.*;


public class HistoricalDataDownloader implements EWrapper, Callable<String> {

    public static final DateTimeFormatter dateTimeFormat = DateTimeFormatter.ofPattern("yyyyMMdd HH:mm:ss"); //format for intraday data
    public static final DateTimeFormatter dateFormat = DateTimeFormatter.ofPattern("yyyyMMdd"); //format for non-intraday data 
    public static final String lineDelimiter = System.lineSeparator(); //newline delimiter
    public static final Set<Integer> nonErrorCodes = Set.of(2104, 2106, 2158); //IB error codes representing data farm connection notifications rather than actual errors
    
    private EClientSocket client; //socket obj to send TWS requests
    private EReaderSignal readerSignal; 
    private EReader reader; //reader thread obj, to handle message queue I/O  

    private Contract contract = new Contract(); //IBKR Contract obj
    private String ticker; 
    private String reqEndDateTime; 
    private String reqLookbackWindow; //from end datetime, how long to retrieve
    private String reqBarSize; //data granularity
    private String reqDataType;
    final private int portNumber = 7496;

    private boolean IsRequestFinsihed = false; //flag to mark end of data request
    private String result;
    private StringBuilder resultBuilder = new StringBuilder(); //for holding returned data

    /*
    Constructor
    */
    public HistoricalDataDownloader(String ticker, String reqEndDateTime, String reqLookbackWindow, String reqBarSize, String reqDataType) {
        this.ticker = ticker;
        this.reqEndDateTime = reqEndDateTime;
        this.reqLookbackWindow = reqLookbackWindow;
        this.reqBarSize = reqBarSize;
        this.reqDataType = reqDataType;
    }
    
    public static void main (String[] args) {

        HistoricalDataDownloader downloader = new HistoricalDataDownloader("AAPL", "20240621 16:00:00 America/New_York", "1 D", "1 hour", "TRADES");
        downloader.openConnection(downloader.portNumber);
      
        downloader.request();

        while ( !downloader.IsRequestFinsihed ) {

            downloader.readerSignal.waitForSignal();

            try {
                downloader.reader.processMsgs(); 
            } catch (IOException err) {
                System.out.println("Error occurred during data read: " + err.getMessage());
            }

        }

        System.out.println(downloader.resultBuilder.toString());
        System.exit(0);
   
    }

    @Override
    public String call() {
        return "something";
    }
    
    /*
    setting variables for the Contract object
    @see https://interactivebrokers.github.io/tws-api/classIBApi_1_1Contract.html
    */
    private void setContract() {
        this.contract.symbol(this.ticker);
        this.contract.secType("STK");
        this.contract.currency("USD");
        this.contract.exchange("SMART"); 
    }

    /*
    reqHistoricalData is the EClient method to request historical data and its callback is HistoricalData()
    @param int Id: uniquie id to tag the request
    @param Contract contract: Contract object representing the underlying
    @param String endDateTime: yyyyMMdd HH:mm:ss format or empty for current
    @param String dataWindow: "<digit> DurationString" where DurationString is S = seconds, D = day, W = week, M = month, Y = year
    @param String dataSize: "<digit> SizeString", valid strings are <1/5/10/15/30> secs, <1/2/3/5/10/15/20/30> mins, <1/2/3/4/8> hours, <1> day/week/month; note 1 min and 1 hour (no s)
    @param String dataType: BID, ASK, MIDPOINT, TRADES
    @param bool RegularHoursOnly: true to use regular market hours
    @param bool dataDateFormat: true for yyyyMMdd HH:mm:ss
    @param bool KeepUpToDate: false
    @param List options: null
    @see: https://ibkrcampus.com/ibkr-api-page/twsapi-doc/#requesting-historical-bars
    */
    private void request() {
        this.setContract();
        this.client.reqHistoricalData(1, this.contract, this.reqEndDateTime, this.reqLookbackWindow, this.reqBarSize, this.reqDataType, 1, 1, false, null);
    }


    /*
    data requested by reqHistoricalData() will be received inside this callback; EReader pushes incoming messages into queue, processMsgs() will call Encoder to check message type and invoke relevant callback.
    callback invoked once per message; so if a request involes x data points (messages), this is called x times
    @param reqId: request identifer 
    @param bar: data in OHLC bar, with getters as <varName()> such as open() for open, volume() for volume
    @see: https://ibkrcampus.com/ibkr-api-page/twsapi-ref/#ewrapper-pub-func
    */
    @Override
    public void historicalData(int reqId, Bar candlestick) {

        String datetimestamp = candlestick.time(); //return format is yyyymmdd or yyyymmdd hh:mm:ss TZ
        String open = String.valueOf( candlestick.open() );
        String close = String.valueOf( candlestick.close() );

        String[] data = {datetimestamp, open, close};
        String csv = Stream.of(data).collect(Collectors.joining(", ")); 
        this.resultBuilder.append(csv + lineDelimiter);

    }

    /*
    If reqHistoricalData used keepUpToDate = false, once all data points for a request have been received in HistoricalData(), this callback is invoked  
    */
    @Override
    public void historicalDataEnd(int reqId, String startDateStr, String endDateStr) {
        this.IsRequestFinsihed = true;
    }
    
    public void historicalSchedule(int reqId, String startDateTime, String endDateTime, String timeZone, List<HistoricalSession> sessions) {
    }

    public void nextValidId(int orderId) {
    }
    
    private void openConnection(int port) { //open socket connection
        this.readerSignal = new EJavaSignal(); 
        this.client = new EClientSocket(this, this.readerSignal);
        this.client.eConnect("127.0.0.1", port, 0); 
        this.reader = new EReader(this.client, this.readerSignal); 
        this.reader.start(); //open a reader thread to starting listening for messages and placing into queue, then invoke issueSignal()
    }

    private void closeConnection() { //closing socket connection and terminating thread
        this.client.eDisconnect(); //socket closing method
    }

    @Override
    public void error(Exception e) {
        System.out.println("Error occurred: " + e.getMessage());
    }

    @Override
    public void error(String str) {
        System.out.println(str);
    }

    @Override
    public void error(int id, int errorCode, String errorMsg, String advancedOrderRejectJson) {
        if ( nonErrorCodes.contains(errorCode) ) { //when the error code represents a notification rather than actual error
            ; //do nothing
        } else {
            System.out.println("Error occurred: code " + errorCode + ", " + errorMsg);
        }
    }

    /*
    The type of data to request, possible options are BID, ASK, TRADES (open and close refer to the first and last traded price), MIDPOINT, and BID_ASK (time-average bid ask prices) 
    */
    private enum PriceDataType {
        BID, 
        ASK,
        MIDPOINT,
        TRADES
    }

    /*
    API can connect to the live/production account for actual trading or paper account for testing, live and paper accounts use ports 7496 and 7497
    */
    private enum LoginAccountType {
        LIVE, 
        PAPER;

        //return the corresponding port number as string
        @Override 
        public String toString() {
            String portNumber = switch(this) {
                case LIVE -> "7496";
                case PAPER -> "7497";
            };
            return portNumber;
        }

        //return the corresponding port number as int
        public int getPort() {
            return Integer.valueOf(this.toString());
        }
    }

    //all irrelevant EWrapper interface callback functions, left empty
    public void tickPrice(int tickerId, int field, double price, TickAttrib attrib) {
    }

    public void tickSize(int tickerId, int field, Decimal size) {
    }

    public void tickOptionComputation(int tickerId, int field, int tickAttrib, double impliedVol, double delta, double optPrice, double pvDividend, double gamma, double vega, double theta, double undPrice) {
    }

    public void tickGeneric(int tickerId, int tickType, double value) {
    }

    public void tickString(int tickerId, int tickType, String value) {
    }

    public void tickEFP(int tickerId, int tickType, double basisPoints, String formattedBasisPoints, double impliedFuture, int holdDays, String futureLastTradeDate, double dividendImpact, double dividendsToLastTradeDate) {
    }

    public void orderStatus(int orderId, String status, Decimal filled, Decimal remaining, double avgFillPrice, int permId, int parentId, double lastFillPrice, int clientId, String whyHeld, double mktCapPrice) {
    }

    public void openOrder(int orderId, Contract contract, Order order, OrderState orderState) {
    }

    public void openOrderEnd() {
    }

    public void updateAccountValue(String key, String value, String currency, String accountName) {
    }

    public void updatePortfolio(Contract contract, Decimal position, double marketPrice, double marketValue, double averageCost, double unrealizedPNL, double realizedPNL, String accountName){
    }

    public void updateAccountTime(String timeStamp) {
    }

    public void accountDownloadEnd(String accountName) {
    }

    public void contractDetails(int reqId, ContractDetails contractDetails) {
    }

    public void bondContractDetails(int reqId, ContractDetails contractDetails) {
    }

    public void contractDetailsEnd(int reqId) {
    }

    public void execDetails(int reqId, Contract contract, Execution execution) {
    }

    public void execDetailsEnd(int reqId) {
    }

    public void updateMktDepth(int tickerId, int position, int operation, int side, double price, Decimal size) {
    }

    public void updateMktDepthL2(int tickerId, int position, String marketMaker, int operation, int side, double price, Decimal size, boolean isSmartDepth) {
    }

    public void updateNewsBulletin(int msgId, int msgType, String message, String origExchange) {
    }

    public void managedAccounts(String accountsList) {
    }

    public void receiveFA(int faDataType, String xml) {
    }

    public void scannerParameters(String xml) {
    }

    public void scannerData(int reqId, int rank, ContractDetails contractDetails, String distance, String benchmark, String projection, String legsStr) {
    }

    public void scannerDataEnd(int reqId) {
    }

    public void realtimeBar(int reqId, long time, double open, double high, double low, double close, Decimal volume, Decimal wap, int count) {
    }

    public void currentTime(long time) {
    }

    public void fundamentalData(int reqId, String data) {
    }

    public void deltaNeutralValidation(int reqId, DeltaNeutralContract deltaNeutralContract) {
    }

    public void tickSnapshotEnd(int reqId) {
    }

    public void marketDataType(int reqId, int marketDataType) {
    }

    public void commissionReport(CommissionReport commissionReport) {
    }

    public void position(String account, Contract contract, Decimal pos, double avgCost) {
    }

    public void positionEnd() {
    }

    public void accountSummary(int reqId, String account, String tag, String value, String currency) {
    }

    public void accountSummaryEnd(int reqId) {
    }

    public void verifyMessageAPI(String apiData) {
    }

    public void verifyCompleted(boolean isSuccessful, String errorText) {
    }

    public void verifyAndAuthMessageAPI(String apiData, String xyzChallenge) {
    }

    public void verifyAndAuthCompleted( boolean isSuccessful, String errorText) {
    }

    public void displayGroupList( int reqId, String groups) {
    }

    public void displayGroupUpdated( int reqId, String contractInfo) {
    }

    public void connectionClosed() {
    }

    public void connectAck() {
    }

    public void positionMulti( int reqId, String account, String modelCode, Contract contract, Decimal pos, double avgCost) {
    }

    public void positionMultiEnd( int reqId) {
    }

    public void accountUpdateMulti( int reqId, String account, String modelCode, String key, String value, String currency) {
    }

    public void accountUpdateMultiEnd( int reqId) {
    }

    public void securityDefinitionOptionalParameter(int reqId, String exchange, int underlyingConId, String tradingClass, String multiplier, Set<String> expirations, Set<Double> strikes) {
    }

    public void securityDefinitionOptionalParameterEnd(int reqId) {
    }

    public void softDollarTiers(int reqId, SoftDollarTier[] tiers) {
    }

    public void familyCodes(FamilyCode[] familyCodes) {
    }

    public void symbolSamples(int reqId, ContractDescription[] contractDescriptions) {
    }

    public void mktDepthExchanges(DepthMktDataDescription[] depthMktDataDescriptions) {
    }

    public void tickNews(int tickerId, long timeStamp, String providerCode, String articleId, String headline, String extraData) {
    }

    public void smartComponents(int reqId, Map<Integer, Entry<String, Character>> theMap) {
    }

    public void tickReqParams(int tickerId, double minTick, String bboExchange, int snapshotPermissions) {
    }

    public void newsProviders(NewsProvider[] newsProviders) {
    }

    public void newsArticle(int requestId, int articleType, String articleText) {
    }

    public void historicalNews(int requestId, String time, String providerCode, String articleId, String headline) {
    }

    public void historicalNewsEnd(int requestId, boolean hasMore) {
    }

    public void headTimestamp(int reqId, String headTimestamp) {
    }

    public void histogramData(int reqId, List<HistogramEntry> items) {
    }

    public void historicalDataUpdate(int reqId, Bar bar) {
    }

    public void rerouteMktDataReq(int reqId, int conId, String exchange) {
    }

    public void rerouteMktDepthReq(int reqId, int conId, String exchange) {
    }

    public void marketRule(int marketRuleId, PriceIncrement[] priceIncrements) {
    }

    public void pnl(int reqId, double dailyPnL, double unrealizedPnL, double realizedPnL) {
    }

    public void pnlSingle(int reqId, Decimal pos, double dailyPnL, double unrealizedPnL, double realizedPnL, double value) {
    }

    public void historicalTicks(int reqId, List<HistoricalTick> ticks, boolean done) {
    }

    public void historicalTicksBidAsk(int reqId, List<HistoricalTickBidAsk> ticks, boolean done) {
    }

    public void historicalTicksLast(int reqId, List<HistoricalTickLast> ticks, boolean done) {
    }

    public void tickByTickAllLast(int reqId, int tickType, long time, double price, Decimal size, TickAttribLast tickAttribLast, String exchange, String specialConditions){
    }

    public void tickByTickBidAsk(int reqId, long time, double bidPrice, double askPrice, Decimal bidSize, Decimal askSize, TickAttribBidAsk tickAttribBidAsk) {
    }

    public void tickByTickMidPoint(int reqId, long time, double midPoint) {   
    }

    public void orderBound(long orderId, int apiClientId, int apiOrderId) {
    }

    public void completedOrder(Contract contract, Order order, OrderState orderState) {
    }

    public void completedOrdersEnd() {
    }

    public void replaceFAEnd(int reqId, String text) {
    }

    public void wshMetaData(int reqId, String dataJson) {
    }

    public void wshEventData(int reqId, String dataJson) {
    }

    public void userInfo(int reqId, String whiteBrandingId) {
    }



    

}
