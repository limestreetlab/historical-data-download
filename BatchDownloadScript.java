package historicalData;

import java.nio.file.*;
import java.nio.*;
import java.util.*;
import java.io.*;

/*
script to send download requests for multiple tickers, read from a file
tickers in the file should be one ticker per line
*/
public class BatchDownloadScript {

    public static void main(String[] args) throws IOException, IllegalArgumentException {
        //local variables
        HistoricalDataDownloader downloader;
        Path tickersPath;
        String dir;
        Path dirPath;
        List<String> tickers;
        int year;
        int month;
        int day;
        String period; //"<digit> DurationString" where DurationString is S = seconds, D = day, W = week, M = month, Y = year
        String dataSize; //"<digit> SizeString", valid strings are <1/5/10/15/30> secs, <1/2/3/5/10/15/20/30> mins, <1/2/3/4/8> hours, <1> day/week/month; note 1 min and 1 hour (no s)
        //getting cmd inputs
        Scanner scanner = new Scanner(System.in);
        System.out.println("Enter path to ticker list file: ");
        tickersPath = Paths.get( scanner.nextLine().trim() );
        System.out.println("Enter directory path to save data in: ");
        dir = scanner.nextLine().trim();
        dirPath = Paths.get( dir );
        System.out.println("Enter request end year: ");
        year = Integer.parseInt( scanner.nextLine().trim() );
        System.out.println("Enter request end month: ");
        month = Integer.parseInt( scanner.nextLine().trim() );
        System.out.println("Enter request end day: ");
        day = Integer.parseInt( scanner.nextLine().trim() );
        System.out.println("Enter request period (digit + D=day, W=week, M=month, Y=year): ");
        period = scanner.nextLine().trim().toUpperCase();
        System.out.println("Enter request bar size (digit + mins/hours/day/week/month): ");
        dataSize = scanner.nextLine().trim().toLowerCase();
        scanner.close();
        //checking paths
        if (!Files.exists(tickersPath)) {
            throw new IllegalArgumentException("Ticker file path does not exist.");
        }
        if (!Files.exists(dirPath)) {
            throw new IllegalArgumentException("Directory path does not exist.");
        }
        if (Files.isDirectory(tickersPath)) {
            throw new IllegalArgumentException("Ticker path is a directory.");
        }
        if (!Files.isDirectory(dirPath)) {
            throw new IllegalArgumentException("Directory path is not a directory.");
        }
        if (!Files.isReadable(tickersPath)) {
            throw new IllegalArgumentException("Tickers not readable.");
        }
        if (!Files.isWritable(dirPath)) {
            throw new IllegalArgumentException("Directory path not writable.");
        }

        tickers = Files.readAllLines(tickersPath);  //open tickers file, read all lines at once and populate into List

        //loop through the queue, send data requests 1-by-1
        for (String ticker : tickers) { 

            ticker = ticker.trim().toUpperCase();
            System.out.println("Requesting data for " + ticker + "...");
            downloader = HistoricalDataDownloader.getDownloader(ticker, year, month, day, period, dataSize, dir);
            try {
                downloader.start();
                System.out.println(ticker + " request finished.");
                Thread.sleep(10000);
            } catch (Exception err) {
                System.out.println(err.getMessage());
            }

        }

        System.out.println("All requests finished. Goodbye.");

    }

}
