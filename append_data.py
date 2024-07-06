import os
import re

#script to append stock data from files in one folder to the corresponding stock data in another folder
#number of files in both folders must match. Used to add recently downloaded data to existing data stores

pattern = "(\\D{1,5})\\s(\\d{1,2}\\D+)\\s(\\d{8})-(\\d{8}).*" #1-5 character for ticker (dual class stocks can have 5), whitespace, 1-2 digit 1+character for barsize, whitespace, yyyymmdd-yyyymmdd
fromDir = input("Enter the origin folder (contents from) path: ")
toDir = input("Enter the destination folder (append to) path: ")
fromFiles = os.listdir(fromDir) #list of filenames inside dir
toFiles = os.listdir(toDir) 

for fromFile in fromFiles:

    if (fromFile == ".DS_Store"): #check and skip the stupid apple ios file
        continue

    fromFilePath = os.path.join(fromDir, fromFile) #join two strings for path string
    f = open(fromFilePath, "r") 
    lines = f.readlines() #read all contents into a list
    f.close()

    match = re.search(pattern, fromFile) #match filename to regex pattern
    ticker = match.group(1) #extract ticker name in filename
    barsize = match.group(2) #extract barsize in filename
    lastdate = match.group(4)  #extract the second yyyymmdd in yyyymmdd-yyyymmdd

    lookFor = "^" + ticker + " " + barsize + ".*" #search pattern in destination folder, start with ticker, a space, barsize
    match = [f for f in toFiles if re.search(lookFor, f)] 
    toFile = match[0]
    toFilePath = os.path.join(toDir, toFile)

    regex = re.search(pattern, toFile)
    firstdate = regex.group(3)
    renamed = ticker + " " + barsize + " " + firstdate + "-" + lastdate + ".csv"
    
    f = open(toFilePath, "a")
    f.writelines(lines)
    f.close()
    try:
        os.replace(toFilePath, os.path.join(toDir, renamed))
    except:
        print("Error occurred")




    