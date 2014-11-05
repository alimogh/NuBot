/*
 * Copyright (C) 2014 desrever <desrever at nubits.com>
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */
package com.nubits.nubot.tasks;

import com.nubits.nubot.global.Constant;
import com.nubits.nubot.global.Global;
import com.nubits.nubot.models.ApiResponse;
import com.nubits.nubot.models.LastPrice;
import com.nubits.nubot.notifications.HipChatNotifications;
import com.nubits.nubot.notifications.MailNotifications;
import com.nubits.nubot.notifications.jhipchat.messages.Message.Color;
import com.nubits.nubot.pricefeeds.PriceFeedManager;
import com.nubits.nubot.utils.FileSystem;
import com.nubits.nubot.utils.Utils;
import java.util.ArrayList;
import java.util.Date;
import java.util.TimerTask;
import java.util.logging.Logger;

/**
 *
 * @author desrever <desrever at nubits.com>
 *
 */
public class PriceMonitorTriggerTask extends TimerTask {

    private int count;
    private final int MAX_ATTEMPTS = 5;
    private static final Logger LOG = Logger.getLogger(PriceMonitorTriggerTask.class.getName());
    private PriceFeedManager pfm = null;
    private StrategySecondaryPegTask strategy = null;
    private double distanceTreshold;
    private LastPrice lastPrice;
    private boolean isFirstTimeExecution = true;
    private LastPrice currentWallPEGPrice;
    private double wallchangeThreshold;
    private double sellPriceUSD, buyPriceUSD;
    private String outputPath;
    private String emailHistory = "";
    private String pegPriceDirection;
    private double sellPricePEG_old;
    private boolean wallsBeingShifted = false;

    @Override
    public void run() {
        LOG.fine("Executing task : PriceMonitorTriggerTask ");
        if (pfm == null || strategy == null) {
            LOG.severe("PriceMonitorTriggerTask task needs a PriceFeedManager and a Strategy to work. Please assign it before running it");

        } else {
            count = 1;
            executeUpdatePrice(count);
        }
    }

    private void executeUpdatePrice(int countTrials) {
        if (countTrials <= MAX_ATTEMPTS) {
            ArrayList<LastPrice> priceList = pfm.getLastPrices().getPrices();

            LOG.fine("CheckLastPrice received values from remote feeds. ");

            LOG.fine("Positive response from " + priceList.size() + "/" + pfm.getFeedList().size() + " feeds");
            for (int i = 0; i < priceList.size(); i++) {
                LastPrice tempPrice = priceList.get(i);
                LOG.fine(tempPrice.getSource() + ":1 " + tempPrice.getCurrencyMeasured().getCode() + " = "
                        + tempPrice.getPrice().getQuantity() + " " + tempPrice.getPrice().getCurrency().getCode());
            }

            if (priceList.size() == pfm.getFeedList().size()) {
                //All feeds returned a positive value
                //Check if mainPrice is close enough to the others
                // I am assuming that mainPrice is the first element of the list
                if (sanityCheck(priceList, 0)) {
                    //mainPrice is reliable compared to the others
                    this.updateLastPrice(priceList.get(0));
                } else {
                    //mainPrice is not reliable compared to the others
                    //Check if other backup prices are close enough to each other
                    boolean foundSomeValidBackUp = false;
                    LastPrice goodPrice = null;
                    for (int l = 1; l < priceList.size(); l++) {
                        if (sanityCheck(priceList, l)) {
                            goodPrice = priceList.get(l);
                            foundSomeValidBackUp = true;
                            break;
                        }
                    }

                    if (foundSomeValidBackUp) {
                        //goodPrice is a valid price backup!
                        this.updateLastPrice(goodPrice);
                    } else {
                        //None of the source are in accord with others.
                        //Try to send a notification
                        unableToUpdatePrice(priceList);
                    }
                }
            } else {
                //One or more feed returned an error value

                if (priceList.size() == 2) { // if only 2 values are available
                    if (closeEnough(priceList.get(0).getPrice().getQuantity(), priceList.get(1).getPrice().getQuantity())) {
                        this.updateLastPrice(priceList.get(0));
                    } else {
                        //The two values are too unreliable
                        unableToUpdatePrice(priceList);
                    }
                } else if (priceList.size() > 2) { // more than two
                    //Check if other backup prices are close enough to each other
                    boolean foundSomeValidBackUp = false;
                    LastPrice goodPrice = null;
                    for (int l = 1; l < priceList.size(); l++) {
                        if (sanityCheck(priceList, l)) {
                            goodPrice = priceList.get(l);
                            foundSomeValidBackUp = true;
                            break;
                        }
                    }
                    if (foundSomeValidBackUp) {
                        //goodPrice is a valid price backup!
                        this.updateLastPrice(goodPrice);
                    } else {
                        //None of the source are in accord with others.
                        //Try to send a notification
                        unableToUpdatePrice(priceList);
                    }
                } else {//if only one or 0 feeds are positive
                    unableToUpdatePrice(priceList);
                }
            }
        } else {
            //Tried more than three times without success
            LOG.severe("The price has failed updating more than " + MAX_ATTEMPTS + " times in a row");
            sendErrorNotification();
        }
    }

    private void unableToUpdatePrice(ArrayList<LastPrice> priceList) {
        count++;
        try {
            Thread.sleep(count * 60 * 1000);
        } catch (InterruptedException ex) {
            LOG.severe(ex.toString());
        }
        executeUpdatePrice(count);
    }

    private void sendErrorNotification() {
        if (Global.options != null) {
            String title = "Problems while updating " + pfm.getPair().getOrderCurrency().getCode() + " price. Cannot find a reliable feed.";
            String message = "NuBot timed out after " + MAX_ATTEMPTS + " failed attempts to update " + pfm.getPair().getOrderCurrency().getCode() + ""
                    + " price. Please restart the bot and get in touch with Nu Dev team";
            MailNotifications.send(Global.options.getMailRecipient(), title, message);
            HipChatNotifications.sendMessage(title + message, Color.RED);
            LOG.severe(title + message);
        }
    }

    private boolean sanityCheck(ArrayList<LastPrice> priceList, int mainPriceIndex) {
        //Measure if mainPrice is close to other two values

        boolean[] ok = new boolean[priceList.size() - 1];
        double mainPrice = priceList.get(mainPriceIndex).getPrice().getQuantity();

        //Test mainPrice vs backup sources
        int f = 0;
        for (int i = 0; i < priceList.size(); i++) {
            if (i != mainPriceIndex) {
                LastPrice tempPrice = priceList.get(i);
                double temp = tempPrice.getPrice().getQuantity();
                ok[f] = closeEnough(mainPrice, temp);
                f++;
            }
        }

        int countOk = 0;
        for (int j = 0; j < ok.length; j++) {
            if (ok[j]) {
                countOk++;
            }
        }

        boolean overallOk = false; //is considered ok if the mainPrice is closeEnough to more than a half of backupPrices
        //Need to distinguish pair vs odd
        if (ok.length % 2 == 0) {
            if (countOk >= (int) ok.length / 2) {
                overallOk = true;
            }
        } else {
            if (countOk > (int) ok.length / 2) {
                overallOk = true;
            }
        }

        return overallOk;

    }
    //if temp differs from mainPrice for more than a threshold%, return false

    private boolean closeEnough(double mainPrice, double temp) {
        double distance = Math.abs(mainPrice - temp);

        double percentageDistance = Utils.round(distance * 100 / mainPrice, 4);
        if (percentageDistance > distanceTreshold) {
            return false;
        } else {
            return true;
        }
    }

    public void updateLastPrice(LastPrice lp) {
        this.lastPrice = lp;
        LOG.fine("Price Updated." + lp.getSource() + ":1 " + lp.getCurrencyMeasured().getCode() + " = "
                + "" + lp.getPrice().getQuantity() + " " + lp.getPrice().getCurrency().getCode() + "\n");
        if (isFirstTimeExecution) {
            initStrategy(lp.getPrice().getQuantity());
            currentWallPEGPrice = lp;
            isFirstTimeExecution = false;
        } else {
            verifyPegPrices();
        }
    }

    public LastPrice getLastPriceFromFeeds() {
        return this.lastPrice;
    }

    private void verifyPegPrices() {

        LOG.fine("Executing tryMoveWalls");

        boolean needToMoveWalls = needToMoveWalls(lastPrice);
        //check if price moved more than x% from when the wall was setup
        if (needToMoveWalls && !isWallsBeingShifted()) { //prevent a wall shift trigger if the strategy is already shifting walls.
            LOG.info("Walls needs to be shifted");
            //Compute price for walls

            currentWallPEGPrice = lastPrice;
            computeNewPrices();

        } else {
            LOG.fine("No need to move walls");
            if (isWallsBeingShifted() && needToMoveWalls) {
                LOG.warning("Wall shift is postponed: another process is already shifting existing walls. Will try again on next execution.");
            }
        }
    }

    private boolean needToMoveWalls(LastPrice last) {
        double currentWallPEGprice = currentWallPEGPrice.getPrice().getQuantity();
        double distance = Math.abs(last.getPrice().getQuantity() - currentWallPEGprice);
        double percentageDistance = Utils.round((distance * 100) / currentWallPEGprice, 4);
        LOG.fine("d=" + percentageDistance + "% (old : " + currentWallPEGprice + " new " + last.getPrice().getQuantity() + ")");

        if (percentageDistance < wallchangeThreshold) {
            return false;
        } else {
            return true;
        }
    }

    private void computeNewPrices() {

        //Sell-side custodian sell-wall

        double peg_price = lastPrice.getPrice().getQuantity();

        //convert sell price to PEG
        double sellPricePEG_new = Utils.round(sellPriceUSD / peg_price, 8);
        double buyPricePEG_new = Utils.round(buyPriceUSD / peg_price, 8);

        //check if the price increased or decreased
        if ((sellPricePEG_new - sellPricePEG_old) > 0) {
            this.pegPriceDirection = Constant.UP;
        } else {
            this.pegPriceDirection = Constant.DOWN;
        }

        LOG.info(" Sell Price " + sellPricePEG_new + "\n"
                + "Buy Price  " + buyPricePEG_new);


        //------------ here for output csv

        String source = currentWallPEGPrice.getSource();
        double price = currentWallPEGPrice.getPrice().getQuantity();
        String currency = currentWallPEGPrice.getPrice().getCurrency().getCode();
        String crypto = pfm.getPair().getOrderCurrency().getCode();

        //Call

        strategy.notifyPriceChanged(sellPricePEG_new, buyPricePEG_new, price, pegPriceDirection);

        //Store values in class variable
        sellPricePEG_old = sellPricePEG_new;

        String row = new Date() + ","
                + source + ","
                + crypto + ","
                + price + ","
                + currency + ","
                + sellPricePEG_new + ","
                + buyPricePEG_new + ",";

        String otherPricesAtThisTime = "";

        ArrayList<LastPrice> priceList = pfm.getLastPrices().getPrices();

        for (int i = 0; i < priceList.size(); i++) {
            LastPrice tempPrice = priceList.get(i);
            otherPricesAtThisTime += "{ feed : " + tempPrice.getSource() + " - price : " + tempPrice.getPrice().getQuantity() + "}  ";
        }
        row += otherPricesAtThisTime + "\n";
        LOG.warning(row);

        if (Global.options.isSendMails()) {
            String title = " production (" + Global.options.getExchangeName() + ") [" + pfm.getPair().toString() + "] price changed more than " + wallchangeThreshold + "%";


            String messageNow = row;
            emailHistory += messageNow;



            String tldr = pfm.getPair().toString() + " price changed more than " + wallchangeThreshold + "% since last notification: "
                    + "now is " + price + " " + pfm.getPair().getPaymentCurrency().getCode().toUpperCase() + ".\n"
                    + "Here are the prices the bot used in the new orders : \n"
                    + "Sell at " + sellPricePEG_new + " " + pfm.getPair().getOrderCurrency().getCode().toUpperCase() + " "
                    + "and buy at " + buyPricePEG_new + " " + pfm.getPair().getOrderCurrency().getCode().toUpperCase() + "\n"
                    + "\n#########\n"
                    + "Below you can see the history of price changes. You can copy paste to create a csv report."
                    + "For each row the bot should have shifted the sell/buy walls.\n\n";




            MailNotifications.send(Global.options.getMailRecipient(), title, tldr + emailHistory);
        }
        FileSystem.writeToFile(row, outputPath, true);
    }

    private void initStrategy(double peg_price) {

        Global.conversion = peg_price; //used then for liquidity info
        //Compute the buy/sell prices in USD

        //get the TX fee
        ApiResponse txFeeNTBPEGResponse = Global.exchange.getTrade().getTxFee(Global.options.getPair());
        if (txFeeNTBPEGResponse.isPositive()) {
            double txfee = (Double) txFeeNTBPEGResponse.getResponseObject();
            sellPriceUSD = 1 + (0.01 * txfee);
            if (!Global.options.isDualSide()) {
                sellPriceUSD = sellPriceUSD + Global.options.getPriceIncrement();
            }
            buyPriceUSD = 1 - (0.01 * txfee);

            //Add(remove) the offset % from prices
            sellPriceUSD = sellPriceUSD + ((sellPriceUSD / 100) * Global.options.getSecondaryPegOptions().getPriceOffset());
            buyPriceUSD = buyPriceUSD - ((buyPriceUSD / 100) * Global.options.getSecondaryPegOptions().getPriceOffset());

            LOG.info("Computing USD prices with offset " + Global.options.getSecondaryPegOptions().getPriceOffset() + "%  : sell @ " + sellPriceUSD + " buy @ " + buyPriceUSD);

            //convert sell price to PEG
            double sellPricePEGInitial = Utils.round(sellPriceUSD / peg_price, 8);
            double buyPricePEGInitial = Utils.round(buyPriceUSD / peg_price, 8);

            //store it
            sellPricePEG_old = sellPricePEGInitial;

            LOG.info("Converted price (using 1 " + Global.options.getPair().getPaymentCurrency().getCode() + " = " + peg_price + " USD)"
                    + " : sell @ " + sellPricePEGInitial + " " + Global.options.getPair().getPaymentCurrency().getCode() + ""
                    + "; buy @" + buyPricePEGInitial + " " + Global.options.getPair().getPaymentCurrency().getCode());

            //Assign prices
            ((StrategySecondaryPegTask) (Global.taskManager.getSecondaryPegTask().getTask())).setBuyPricePEG(buyPricePEGInitial);
            ((StrategySecondaryPegTask) (Global.taskManager.getSecondaryPegTask().getTask())).setSellPricePEG(sellPricePEGInitial);
            //Start strategy
            Global.taskManager.getSecondaryPegTask().start();

            //Send email notification
            String title = " production (" + Global.options.getExchangeName() + ") [" + pfm.getPair().toString() + "] price tracking started";
            String tldr = pfm.getPair().getOrderCurrency().getCode().toUpperCase() + " price trackin started at " + peg_price + " " + pfm.getPair().getPaymentCurrency().getCode().toUpperCase() + ".\n"
                    + "Will send a new mail notification everytime the price of " + pfm.getPair().getOrderCurrency().getCode().toUpperCase() + " changes more than " + Global.options.getSecondaryPegOptions().getWallchangeTreshold() + "%.";
            MailNotifications.send(Global.options.getMailRecipient(), title, tldr);
        } else {
            LOG.severe("Cannot get txFee : " + txFeeNTBPEGResponse.getError().getDescription());
            System.exit(0);
        }
    }

    public double getWallchangeThreshold() {
        return wallchangeThreshold;
    }

    public void setWallchangeThreshold(double wallchangeThreshold) {
        this.wallchangeThreshold = wallchangeThreshold;
    }

    public double getSellPriceUSD() {
        return sellPriceUSD;
    }

    public void setSellPriceUSD(double sellPriceUSD) {
        this.sellPriceUSD = sellPriceUSD;
    }

    public double getBuyPriceUSD() {
        return buyPriceUSD;
    }

    public void setBuyPriceUSD(double buyPriceUSD) {
        this.buyPriceUSD = buyPriceUSD;
    }

    public String getOutputPath() {
        return outputPath;
    }

    public void setOutputPath(String outputPath) {
        this.outputPath = outputPath;
    }

    public void setStrategy(StrategySecondaryPegTask strategy) {
        this.strategy = strategy;
    }

    public boolean isWallsBeingShifted() {
        return wallsBeingShifted;
    }

    public void setWallsBeingShifted(boolean wallsBeingShifted) {
        this.wallsBeingShifted = wallsBeingShifted;
    }

    public PriceFeedManager getPfm() {
        return pfm;
    }

    public void setPriceFeedManager(PriceFeedManager pfm) {
        this.pfm = pfm;
    }

    public double getDistanceTreshold() {
        return distanceTreshold;
    }

    public void setDistanceTreshold(double distanceTreshold) {
        this.distanceTreshold = distanceTreshold;
    }
}
