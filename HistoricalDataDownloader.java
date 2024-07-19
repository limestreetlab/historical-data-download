package historicalData;

import com.ib.client.*;
import java.util.stream.*;
import java.util.*;
import java.util.Map.Entry;
import java.util.regex.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.Temporal;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;


public class HistoricalDataDownloader implements EWrapper {

    //the singleton instance of this class
    private static HistoricalDataDownloader downloader;

    //static variables
    private static final int portNumber = 7496; //input port number here, 7696 for live/production account, 7497 for paper account
    private static final DateTimeFormatter dateTimeWithTimezoneFormat = DateTimeFormatter.ofPattern("yyyyMMdd HH:mm:ss VV"); //format for intraday data with timezone, VV for timezone
    private static final DateTimeFormatter dateTimeWithoutTimezoneFormat = DateTimeFormatter.ofPattern("yyyyMMdd HH:mm:ss"); //format for intraday data without timezone, VV for timezone
    private static final DateTimeFormatter dateFormat = DateTimeFormatter.ofPattern("yyyyMMdd"); //format for non-intraday data 
    private static final ZoneId timezone = ZoneId.of("America/New_York"); //Java ZonedDateTime Class timezone obj, always use EST
    private static final Set<Integer> okErrorCodes = Set.of(2104, 2106, 2158, 2108, 2148); //IB error codes representing data connection notifications rather than actual errors, shall be ignored
    //API connection handles
    private EClientSocket client; //socket obj to send TWS requests
    private EReaderSignal readerSignal; //sends signals to reader on message queue status
    private EReader reader; //reader obj to handle message queue, EReader extends Thread and has run()
    //request parameters
    private Contract contract = new Contract(); //IBKR Contract obj used when sending requests
    private List<String> tickers; //container to hold request tickers
    private String reqEndDateTime; //date from which request is backdated
    private String reqPeriod; //from end datetime, how long to retrieve
    private String reqBarSize; //data granularity
    //processing, result, other variables
    private boolean isIntraday; //flag to indicate if data request is intraday or interday
    private boolean isBidRequestDone = false; //flag to mark end of bid request
    private boolean isAskRequestDone = false; //flag to mark end of ask request
    private boolean isTradesRequestDone = false; //flag to mark end of trades request
    private LinkedList<Bid> bids = new LinkedList<>(); //container to accumulate Bid objs
    private LinkedList<Ask> asks = new LinkedList<>(); //container to accumulate Ask objs
    private LinkedList<Trades> trades = new LinkedList<>(); //container to accumulate Trades objs
    private LinkedList<BidAskTrades> bidsAsksTrades = new LinkedList<>(); //container to join bids,asks,trades for intraday
    private Path dirPath; //path to the directory to save this data file
    private boolean withHeader; //if csv output with headers or not

    /*
    Constructor, setting instance variables to the request parameters
    */
    private HistoricalDataDownloader(List<String> tickers, String reqEndDateTime, String reqPeriod, String reqBarSize, String dirPath) throws IllegalArgumentException {
        this.tickers = tickers;
        this.reqEndDateTime = reqEndDateTime;
        this.reqPeriod = reqPeriod;
        this.reqBarSize = reqBarSize;
        this.dirPath = Paths.get(dirPath);
        this.isIntraday = Arrays.stream(new String[]{"sec", "min", "hour"}).anyMatch(reqBarSize::contains) ? true : false; //flag raised for intraday request
    }

    /*
    Constructor accessor, global instantiation point for this class instance
    @param List<String> tickers
    @param String endDateTime year
    @param String endDateTime month
    @param String endDateTime day
    @param String period: "<digit> DurationString" where DurationString is S = seconds, D = day, W = week, M = month, Y = year
    @param String dataSize: "<digit> SizeString", valid strings are <1/5/10/15/30> secs, <1/2/3/5/10/15/20/30> mins, <1/2/3/4/8> hours, <1> day/week/month; note 1 min and 1 hour (no s)
    @param String path: path to the directory where file will be saved
    @param bool withHeader: true to write headers to csv data, false without
    */
    public static HistoricalDataDownloader getDownloader(List<String> tickers, int endYear, int endMonth, int endDay, String reqPeriod, String reqBarSize, String dirPath, boolean withHeader) throws IllegalArgumentException {
        String reqEndDateTime = makeDateTime(endYear, endMonth, endDay); //convert to valid datetime format defined
       
        downloader = new HistoricalDataDownloader(tickers, reqEndDateTime, reqPeriod, reqBarSize, dirPath);
    
        downloader.withHeader = withHeader ? true : false;

        if (!Files.exists(Paths.get(dirPath))) {
            throw new IllegalArgumentException("Input path does not exist.");
        }
        if (!Files.isDirectory(Paths.get(dirPath))) {
            throw new IllegalArgumentException("Input path is not a directory.");
        }
        if (!Files.isWritable(Paths.get(dirPath))) {
            throw new IllegalArgumentException("Input path is not writable.");
        }
        
        return downloader;
    }
    //overloaded version with optional withHeader=true
    public static HistoricalDataDownloader getDownloader(List<String> tickers, int endYear, int endMonth, int endDay, String reqPeriod, String reqBarSize, String dirPath) throws IllegalArgumentException {
        return getDownloader(tickers, endYear, endMonth, endDay, reqPeriod, reqBarSize, dirPath, true);
    }
    //overloaded version with a single ticker as string
    public static HistoricalDataDownloader getDownloader(String ticker, int endYear, int endMonth, int endDay, String reqPeriod, String reqBarSize, String dirPath, boolean withHeader) throws IllegalArgumentException {
        return getDownloader(List.of(ticker), endYear, endMonth, endDay, reqPeriod, reqBarSize, dirPath, withHeader);
    }
    //overloaded version with single ticker as string and optional withHeader=true
    public static HistoricalDataDownloader getDownloader(String ticker, int endYear, int endMonth, int endDay, String reqPeriod, String reqBarSize, String dirPath) throws IllegalArgumentException {
        return getDownloader(List.of(ticker), endYear, endMonth, endDay, reqPeriod, reqBarSize, dirPath, true);
    }
    
    public static void main (String[] args) throws IllegalArgumentException, NumberFormatException, IOException {

        String ticker = null;
        String dateTime = null;
        String period = null;
        String barSize = null;
        String dirPath = null;
        String year = null;
        String month = null;
        String day = null;

        try { //get contract parameters from cmd, then assign
            Map<String, String> parameters = readCmdInputs(); 
            ticker = parameters.get("ticker");
            year = parameters.get("endYear");
            month = parameters.get("endMonth");
            day = parameters.get("endDay");
            period =  parameters.get("period");
            barSize = parameters.get("barSize");
            dirPath = parameters.get("path");

        } catch (IllegalArgumentException err) {
            System.out.println(err.getMessage());
            System.exit(0);
        }

        downloader = getDownloader(ticker, Integer.parseInt(year), Integer.parseInt(month), Integer.parseInt(day), period, barSize, dirPath); //construct download instance 
        try {
            downloader.start();
        } catch (Exception err) {
            if (downloader.client.isConnected()) {
                downloader.closeConnection();
            }
            System.out.println(err.getMessage());
        } 
        System.out.println("Data request for " + ticker + " done.");
        
    }

    /*
    main method encapsulating all operations from connect and request to save and disconnect
    */
    public void start() throws IOException, RuntimeException, IllegalArgumentException {

        int loopSize = this.tickers.size(); //number of times to loop
        int currentLoopCount = 1;

        this.openConnection(portNumber); //connect to TWS server
        System.out.println("Connection to TWS established.");

        for (String ticker : this.tickers) { //loop through each ticker in tickers list

            this.setContract(ticker); //set contract to ticker in current loop
            System.out.print("(" + currentLoopCount + "/" + loopSize + ") " + "Requesting data for " + ticker + "...");
            currentLoopCount++;
            
            try { //submit request(s) to server, with predetermined (arbitrarily) IDs for each type

                this.bids.clear(); //reset
                this.asks.clear();
                this.trades.clear();
                this.bidsAsksTrades.clear();

                if (this.isIntraday) { //intraday case, request bid/ask/trades
                    this.request(PriceDataType.TRADES, 1); 
                    this.request(PriceDataType.BID, 2);
                    this.request(PriceDataType.ASK, 3);
                } else { //interday case, request trades only
                    this.request(PriceDataType.TRADES, 1); 
                    this.isBidRequestDone = true; //no bid and ask requests
                    this.isAskRequestDone = true; //no bid and ask requests
                }

                while ( !this.isBidRequestDone || !this.isAskRequestDone || !this.isTradesRequestDone  ) { //loop until request(s) completed

                    this.readerSignal.waitForSignal();
                    
                    try {
                        this.reader.processMsgs(); //trigger callback
                    } catch (IOException err) {
                        throw new IOException(err);
                    }

                } //all request messages received and processed
                this.isBidRequestDone = false; //reset
                this.isAskRequestDone = false;
                this.isTradesRequestDone = false;
                

            } catch (NoDataException err) {
                System.out.println("data for " + ticker + " unavailable, skipping it.");
                continue;
            } catch (RuntimeException err) {
                throw new RuntimeException(err.getMessage());
            }
           
            System.out.print("...data received, proceeding to save...");

            try {
                this.saveData(); //save accumulated data to file
                System.out.println("...saved.");
            } catch (IOException err) {
                throw new IOException(err);
            }

        }

        this.closeConnection(); 

    }

    private void saveData() throws IOException {
        
        String filename;
        Path filePath; 
        String firstDate = this.trades.peek().datetime.substring(0, 8); //oldest datetime in req
        String lastDate = this.trades.peekLast().datetime.substring(0, 8); //newest datetime in req

        filename = this.contract.symbol() + " " + this.reqBarSize.replaceAll("\\s", "") + " " + firstDate + "-" + lastDate + ".csv"; //filename format "AAPL 1min yyyymmdd-yyyymmdd.csv"
        filePath = this.dirPath.resolve(filename);

        try (BufferedWriter writer = Files.newBufferedWriter(filePath)) {
        
            if (this.isIntraday) {
                this.joinBidAskTrades();
                if (this.withHeader) {
                    writer.write("datetime, bid, ask, open, high, low, close, volume"); //csv header
                    writer.newLine();
                }
                for (BidAskTrades line : this.bidsAsksTrades) {
                    writer.write(line.toString() + System.lineSeparator());
                }   
            } else {
                if (this.withHeader) {
                    writer.write("datetime, open, high, low, close, volume"); //csv header
                    writer.newLine();
                }
                for (Trades line : this.trades) {
                    writer.write(line.toString() + System.lineSeparator());
                }
            }
        } catch (IOException err) {
            throw new IOException("Error occurred when writing data to file for " + this.contract.symbol());
        }
    
    }

    /*
    Utility function to help read user cmd inputs <ticker, endDateTime, period, barSize> and return as Map
    @return {"ticker": ticker, "dateTime": dateTime, "period": period, "barSize": barSize}
    */
    private static Map<String, String> readCmdInputs() throws IllegalArgumentException {

        String ticker = null;
        String dateTime = null;
        int year;
        int month;
        int day;
        String duration; //user inputted period
        String durationDigit; //digit part
        String durationUnit; //ex-digit part
        String durationString; //converted to API string
        String period; //re-concatenated
        String interval; //user inputted barsize
        String intervalDigit; //digit part
        String intervalUnit; //ex-digit part
        String intervalString; //converted to API string
        String barSize; //re-concatenated
        String path; //directory path to save data
        Set<String> validSecMinIntervalDigit = Set.of("1", "5", "10", "15", "30"); 
        final Pattern inputPattern = Pattern.compile("(\\d+)(\\D+)"); //regex to split request into digit and character groups, like 100 day -> (100)( day)
        Matcher regexMatcher;

        Scanner scanner = new Scanner(System.in);
    
        //ticker read
        System.out.println("Enter ticker: ");
        ticker = scanner.nextLine().trim().toUpperCase();
        if ( ticker.length() == 0 || ticker.length() > 4 || ticker.contains(" ") ) {
            throw new IllegalArgumentException("Invalid ticker.");
        }
        //datetime read
        try {
            System.out.println("Enter request end year: ");
            year = Integer.valueOf( scanner.nextLine().trim() );
            System.out.println("Enter request end month: ");
            month = Integer.valueOf( scanner.nextLine().trim() );
            System.out.println("Enter request end day: ");
            day = Integer.valueOf( scanner.nextLine().trim() );
            if ( day < 1 || day > 31 || month < 1 || month > 12 || year > Year.now().getValue() || year < Year.now().getValue()-5 ) {
                throw new IllegalArgumentException("Invalid input date.");
            }
        } catch (NumberFormatException err) {
            throw new NumberFormatException("Invalid input date."); //cannot parse to int
        }
        //period read
        System.out.println("Enter data request period (such as 5 days, 1 week): ");
        duration = scanner.nextLine().trim();
        regexMatcher = inputPattern.matcher(duration); //match input to regex
        if (!regexMatcher.matches()) {
            throw new IllegalArgumentException("Invalid request period format.");
        }
        durationDigit = regexMatcher.group(1); //the number portion
        durationUnit = regexMatcher.group(2).trim().toLowerCase(); //after the number portion
        if (durationUnit.contains("day") || durationUnit.substring(0,1).equals("d")) {
            durationString = "D";
        } else if (durationUnit.contains("week") || durationUnit.substring(0,1).equals("w")) {
            durationString = "W";
        } else if (durationUnit.contains("month") || durationUnit.substring(0,1).equals("m")) {
            durationString = "M";
        } else if (durationUnit.contains("year") || durationUnit.substring(0,1).equals("y")) {
            durationString = "Y";
        } else {
            throw new IllegalArgumentException("Could not identify period requested.");
        }
        period = durationDigit + " " + durationString;
        //read barsize
        System.out.println("Enter data interval (such as 5 minutes, 1 hour, 1 day): ");
        interval = scanner.nextLine().trim();
        regexMatcher = inputPattern.matcher(interval); //match input to regex
        if (!regexMatcher.matches()) {
            throw new IllegalArgumentException("Invalid request bar size format.");
        }
        intervalDigit = regexMatcher.group(1); //the number portion
        intervalUnit = regexMatcher.group(2).trim().toLowerCase(); //after the number portion
        if ( intervalUnit.contains("sec") || intervalUnit.substring(0,1).equals("s") ) {
            intervalString = "secs";
        } else if ( intervalUnit.contains("min") || intervalUnit.substring(0,1).equals("m") ) {
            intervalString = "mins";
        } else if ( intervalUnit.contains("hour") || intervalUnit.contains("hr") || intervalUnit.substring(0,1).equals("h") ) {
            intervalString = "hours";
        } else if ( intervalUnit.contains("day") || intervalUnit.substring(0,1).equals("d") ) {
            intervalString = "day";
        } else if ( intervalUnit.contains("week") || intervalUnit.contains("wk") || intervalUnit.substring(0,1).equals("w") ) {
            intervalString = "week";
        } else if ( intervalUnit.contains("year") || intervalUnit.contains("yr") || intervalUnit.substring(0,1).equals("y") ) {
            intervalString = "year";
        } 
        else {
            throw new IllegalArgumentException("Could not identify bar interval requested.");
        }
        if ( (intervalString.equals("secs") || intervalString.equals("mins")) && !(validSecMinIntervalDigit.stream().anyMatch(intervalDigit::equals)) ) { //sec or min interval with digit outside of valid values
            throw new IllegalArgumentException("Only 1/5/10/15/30 allowed for seconds and minutes.");
        } else if ( (intervalString.equals("hours") || intervalString.equals("day") || intervalString.equals("week") || intervalString.equals("year")) && !intervalDigit.equals("1") ) { //hour, day, week, year interval, with digit not 1
            throw new IllegalArgumentException("Only 1 hour/day/week/year accepted.");
        }
        if ( intervalDigit.equals("1") && intervalString.charAt(intervalString.length()-1) == "s".charAt(0) ) { //when digit is 1 and last string char is 's'
            intervalString = intervalString.substring(0, intervalString.length()-1); //remove the 's'
        }
        barSize = intervalDigit + " " + intervalString;
        //read directory path
        System.out.println("Enter path to directory to save file in: ");
        path = scanner.nextLine().trim();

        scanner.close();

        return Stream.of(new String[][] { {"ticker", ticker}, {"endYear", String.valueOf(year)}, {"endMonth", String.valueOf(month)}, {"endDay", String.valueOf(day)}, {"period", period}, {"barSize", barSize}, {"path", path} } ).collect(Collectors.toMap(el -> el[0], el -> el[1]));

    }

    /*
    @return string in IBAPI dateTime format with timezone specified
    */
    static private String makeDateTime(int year, int month, int day) throws IllegalArgumentException {
        ZonedDateTime dateTime = null;
        try {
            dateTime = ZonedDateTime.of(year, month, day, 16, 0, 0, 0, timezone); //creating a ZonedDateTime obj
        } 
        catch (DateTimeException err) {
            throw new IllegalArgumentException("Invalid date input.");
        }
        return dateTime.format(dateTimeWithTimezoneFormat); //format to string
    }

    /*
    @return string in IBAPI dateTime format without timezone 
    */
    static private String removeTimezone(String dateTimeWithTimezone) {
        LocalDateTime dateTime = LocalDateTime.parse(dateTimeWithTimezone, dateTimeWithTimezoneFormat); //create a DateTime obj from string, tz removed
        return dateTime.format(dateTimeWithoutTimezoneFormat); //format to string
    }
    
    /*
    setting variables for the Contract object
    @see https://interactivebrokers.github.io/tws-api/classIBApi_1_1Contract.html
    */
    private void setContract(String ticker) {
        this.contract.symbol(ticker.trim().toUpperCase());
        this.contract.secType("STK");
        this.contract.currency("USD");
        this.contract.exchange("SMART"); 
    }

    /*
    reqHistoricalData is the EClient method to request historical data and its callback is HistoricalData()
    @param int Id: uniquie id to tag the request
    @param Contract contract: Contract object representing the underlying
    @param String endDateTime: yyyyMMdd HH:mm:ss timezone format or empty for current; timezone format in America/New_York
    @param String dataWindow: "<digit> DurationString" where DurationString is S = seconds, D = day, W = week, M = month, Y = year
    @param String dataSize: "<digit> SizeString", valid strings are <1/5/10/15/30> secs, <1/2/3/5/10/15/20/30> mins, <1/2/3/4/8> hours, <1> day/week/month; note 1 min and 1 hour (no s)
    @param String dataType: BID, ASK, MIDPOINT, TRADES
    @param bool RegularHoursOnly: true to use regular market hours
    @param bool dataDateFormat: true for yyyyMMdd HH:mm:ss TZ
    @param bool KeepUpToDate: false
    @param List options: null
    @see: https://ibkrcampus.com/ibkr-api-page/twsapi-doc/#requesting-historical-bars
    */
    private void request(PriceDataType reqDataType, int reqId) throws UncheckedIOException {
        this.client.reqHistoricalData(reqId, this.contract, this.reqEndDateTime, this.reqPeriod, this.reqBarSize, reqDataType.name(), 1, 1, false, null);
    }
    private void request() { //overloaded no arg-request
        this.request(PriceDataType.TRADES, 1);
    }
    private void request(PriceDataType reqDataType) { //overloaded type-only request
        this.request(reqDataType, 1);
    }
    private void cancelRequest(int reqId) {
        this.client.cancelHistoricalData(reqId);
    }

    /*
    data requested by reqHistoricalData() will be received inside this callback; EReader pushes incoming messages into queue, processMsgs() will call Encoder to check message type and invoke relevant callback.
    callback invoked once per message; so if a request involes x data points (messages), this is called x times
    @param reqId: request identifer 
    @param bar: data in OHLC bar, with getters as <varName()> such as open() for open, volume() for volume
    @see: https://ibkrcampus.com/ibkr-api-page/twsapi-ref/#ewrapper-pub-func
    */
    @Override
    public void historicalData(int reqId, Bar candlestick) throws IllegalArgumentException {

        String datetimestamp = this.isIntraday ? removeTimezone(candlestick.time().trim()) : candlestick.time().trim(); //remove timezone only if requested barsize is of intraday timescale, interday data have no tz
        double open = 0;
        double high = 0;
        double low = 0;
        double close = 0;
        double bid = 0;
        double ask = 0;
        long volume = 0;

        if (reqId == 2) { //message is for bid
            bid = candlestick.open();
        } else if (reqId == 3) { //message is for ask
            ask = candlestick.open();
        } else {  //message is for trades
            open = candlestick.open();
            high = candlestick.high();
            low = candlestick.low();
            close = candlestick.close();
            volume = candlestick.volume().longValue(); //volume is of a IBAPI-defined Decimal type
        }

        switch (reqId) {
            case 1 -> this.trades.add(new Trades(datetimestamp, open, high, low, close, volume));
            case 2 -> this.bids.add(new Bid(datetimestamp, bid));
            case 3 -> this.asks.add(new Ask(datetimestamp, ask));
            default -> throw new IllegalArgumentException("Unable to recognise request ID to tag price type, failed to allocate message.");
        }

    }

    /*
    If reqHistoricalData used keepUpToDate = false, once all data points for a request have been received in HistoricalData(), this callback is invoked  
    */
    @Override
    public void historicalDataEnd(int reqId, String startDateStr, String endDateStr) throws IllegalArgumentException {
        switch (reqId) {
            case 1 -> this.isTradesRequestDone = true; //used 1, 2, 3 for trades, bid, ask requests, respectively
            case 2 -> this.isBidRequestDone = true;
            case 3 -> this.isAskRequestDone = true;
            default -> throw new IllegalArgumentException("Unable to recognise request ID in identifying request price type.");
        }
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
        if (this.client.isConnected()) {
            this.closeConnection();
        }
        throw new RuntimeException(e.getMessage());
    }

    @Override
    public void error(String str) {
        System.out.println(str);
        if (this.client.isConnected()) {
            this.closeConnection();
        }
        throw new RuntimeException(str);
    }

    /*
    main callback server invokes to deliver error notifications 
    @See https://interactivebrokers.github.io/tws-api/message_codes.html
    */
    @Override
    public void error(int id, int errorCode, String errorMsg, String advancedOrderRejectJson) throws RuntimeException, IllegalArgumentException {
        if ( okErrorCodes.contains(errorCode) ) { //when the error code represents a notification rather than actual error
            ; //do nothing
        } else if (errorCode == 2103 || errorCode == 2105 || errorCode == 2157) { //data farm broken but will most likely restart (followed by 2104/2106/2158)
            short sleepTime = 1; //sleeping time in minutes (whole number)
            System.out.println( "Data farm connection error " + errorCode + " occured in " +  this.contract.symbol() + ", pausing this thread now for " + sleepTime + " minute.");
            try {
                Thread.sleep(sleepTime * 1000 * 60);
            } catch (InterruptedException err) {
                ;
            }
        } else if (errorCode == 162 && errorMsg.toLowerCase().contains("no data")) { //historical data error message and saying no data for requested dates for a stock (possibly new IPO etc)
            if (this.isIntraday) {
                throw new IllegalArgumentException("No data found for the stock, possibly not traded at the time.");
            } else { //interday
                throw new NoDataException(errorMsg); //signal to caller it is interday case for no data, ticker can be skipped (difficult to do in intraday as multiple requests BID ASK TRADES sent, id complication arises, better let it terminate)
            }
            
        } else {            
            throw new RuntimeException(errorCode + ": " + errorMsg);
        }
    }

    private class NoDataException extends RuntimeException {
        private NoDataException(String msg) {
            super(msg);
        }
    }

    //Type of data to request, possible options are BID, ASK, TRADES (open and close refer to the first and last traded price), MIDPOINT, and BID_ASK (time-average bid ask prices) 
    private enum PriceDataType {
        BID, 
        ASK,
        TRADES,
        MIDPOINT,
        BID_ASK
    }

    private record Bid(String datetime, Double bid) {
    }

    private record Ask(String datetime, Double ask) {
    }

    private record Trades(String datetime, Double open, Double high, Double low, Double close, Long volume) implements Comparable<Trades> {

        @Override   //show datetime, open, high, low, close, volume
        public String toString() { 
            String[] data = {this.datetime, String.valueOf(this.open), String.valueOf(this.high), String.valueOf(this.low), String.valueOf(this.close), String.valueOf(this.volume)};
            return (Stream.of(data).collect(Collectors.joining(", "))); //csv format
        }

        @Override   //obj A is considered larger than B if its datetime is after that of B (ie recent data is larger)
        public int compareTo(Trades that) { 
            Temporal thisTimestamp;
            Temporal thatTimestamp;
            
            if ( this.datetime.equals(that.datetime()) ) {
                return 0;
            }

            if ( this.datetime.length() == 8 ) { //interday data yyyymmdd
                thisTimestamp = LocalDate.parse(this.datetime, dateFormat);
                thatTimestamp = LocalDate.parse(that.datetime(), dateFormat);
            } else { //intraday data
                thisTimestamp = LocalDateTime.parse(this.datetime, dateTimeWithoutTimezoneFormat);
                thatTimestamp = LocalDateTime.parse(that.datetime(), dateTimeWithoutTimezoneFormat);
            }

            return dateTimeCompare(thisTimestamp, thatTimestamp);
        }

    }

    //data object to hold Bid, Ask, and Trades custom types
    private record BidAskTrades(Bid bid, Ask ask, Trades trades) implements Comparable<BidAskTrades> {

        @Override   //obj A is considered larger than B if its datetime is after that of B (ie recent data is larger)
        public int compareTo(BidAskTrades that) { 
            Temporal thisTimestamp;
            Temporal thatTimestamp;
            
            if ( this.bid.datetime.equals(that.bid.datetime()) ) {
                return 0;
            }

            if ( this.bid.datetime.length() == 8 ) { //interday data yyyymmdd
                thisTimestamp = LocalDate.parse(this.bid.datetime, dateFormat);
                thatTimestamp = LocalDate.parse(that.bid.datetime(), dateFormat);
            } else { //intraday data
                thisTimestamp = LocalDateTime.parse(this.bid.datetime, dateTimeWithoutTimezoneFormat);
                thatTimestamp = LocalDateTime.parse(that.bid.datetime(), dateTimeWithoutTimezoneFormat);
            }

            return dateTimeCompare(thisTimestamp, thatTimestamp);
        }

        @Override   //show datetime, bid, ask, open, high, low, close, volume
        public String toString() { 
            String[] data = {this.bid.datetime, String.valueOf(this.bid.bid), String.valueOf(this.ask.ask), String.valueOf(this.trades.open), String.valueOf(this.trades.high), String.valueOf(this.trades.low), String.valueOf(this.trades.close), String.valueOf(this.trades.volume)};
            return (Stream.of(data).collect(Collectors.joining(", "))); //csv format
        }

    }

    //combine the bids, asks, and trades data queues
    private void joinBidAskTrades() throws ArrayIndexOutOfBoundsException {

        //the bids, askas, and trades collections should have identical sizes and supermajority of times are, but for less-liquid stocks trades data can be missing at a timestamp
        if ( !(this.bids.size() == this.asks.size() && this.asks.size() == this.trades.size()) ) {//in case of unequal sizes
            
            if ( (this.bids.size() == this.asks.size()) && (this.trades.size() < this.bids.size()) ) { //commonly trades missing but bids and asks exist, fill null values in trades

                for (int i = 0; i < this.bids.size(); i++) {  //loop through bids
                    if (!(this.bids.get(i).datetime().equals(this.trades.get(i).datetime()))) {  //when a timestamp mismatched
                        this.trades.add(i, new Trades(this.bids.get(i).datetime(), null, null, null, null, null)); //insert into trades collection with null data
                    }
                }

            } else { //very rarely should bids and asks be missing, cannot handle
                throw new ArrayIndexOutOfBoundsException("The bids and asks data are less than trades data for " + this.contract.symbol());
            }

        }

        for (Bid bid: this.bids) {
            Ask ask = this.asks.remove();
            Trades trade = this.trades.remove();
            this.bidsAsksTrades.add(new BidAskTrades(bid, ask, trade));
        }

    }

    //helper method for CompareTo in Comparable<>
    static private int dateTimeCompare(Temporal dateTime1, Temporal dateTime2) {
        int thisYear;
        int thatYear;
        int thisMonth;
        int thatMonth;
        int thisDay;
        int thatDay;
        int thisHour = 0;
        int thatHour = 0;
        int thisMinute = 0;
        int thatMinute = 0;
        int thisSecond = 0;
        int thatSecond = 0;

        if (dateTime1 instanceof LocalDateTime) { //intraday data
            LocalDateTime thisDateTime = (LocalDateTime)dateTime1; 
            LocalDateTime thatDateTime = (LocalDateTime)dateTime2; 
            thisYear = thisDateTime.getYear();
            thatYear = thatDateTime.getYear();
            thisMonth = thisDateTime.getMonthValue();
            thatMonth = thatDateTime.getMonthValue();
            thisDay = thisDateTime.getDayOfMonth();
            thatDay = thatDateTime.getDayOfMonth();
            thisHour = thisDateTime.getHour();
            thatHour =  thatDateTime.getHour();
            thisMinute = thisDateTime.getMinute();
            thatMinute = thatDateTime.getMinute();
            thisSecond = thisDateTime.getSecond();
            thatSecond = thatDateTime.getSecond();
        } else { //interday data
            LocalDate thisDateTime = (LocalDate)dateTime1; 
            LocalDate thatDateTime = (LocalDate)dateTime2; 
            thisYear = thisDateTime.getYear();
            thatYear = thatDateTime.getYear();
            thisMonth = thisDateTime.getMonthValue();
            thatMonth = thatDateTime.getMonthValue();
            thisDay = thisDateTime.getDayOfMonth();
            thatDay = thatDateTime.getDayOfMonth();
        }

        if (thisYear == thatYear && thisMonth == thatMonth && thisDay == thatDay) { //same dates, intraday comparison
            if (thisHour > thatHour) {
                return 1;
            } else if (thisHour < thatHour) {
                return -1;
            } else { //same hour
                if (thisMinute > thatMinute) { 
                    return 1;
                } else if (thisMinute < thatMinute) {
                    return -1;
                } else { //same hour and minute
                    if (thisSecond > thatSecond) {
                        return 1;
                    } else {
                        return -1;
                    }
                }
            }

        } else { //different dates
            if (thisYear > thatYear) {
                return 1;
            } else if (thisYear < thatYear) {
                return -1;
            } else { //same year
                if (thisMonth > thatMonth) {
                    return 1;
                } else if (thisMonth < thatMonth) {
                    return -1;
                } else { //same year and month
                    if (thisDay > thatDay) {
                        return 1;
                    } else {
                        return -1;
                    }
                }
            }
        } 
    }

    //all irrelevant EWrapper interface callback functions, left empty
    public void nextValidId(int orderId) {
    }

    public void historicalSchedule(int reqId, String startDateTime, String endDateTime, String timeZone, List<HistoricalSession> sessions) {
    }

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
