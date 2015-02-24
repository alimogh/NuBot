/*
 * Copyright (C) 2014-2015 Nu Development Team
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
package com.nubits.nubot.launch;

import com.nubits.nubot.RPC.NuRPCClient;
import com.nubits.nubot.exchanges.Exchange;
import com.nubits.nubot.exchanges.ExchangeLiveData;
import com.nubits.nubot.global.Constant;
import com.nubits.nubot.global.Global;
import com.nubits.nubot.models.ApiResponse;
import com.nubits.nubot.models.Currency;
import com.nubits.nubot.models.CurrencyPair;
import com.nubits.nubot.notifications.HipChatNotifications;
import com.nubits.nubot.options.NuBotConfigException;
import com.nubits.nubot.options.NuBotOptionsDefault;
import com.nubits.nubot.options.ParseOptions;
import com.nubits.nubot.options.SecondaryPegOptionsJSON;
import com.nubits.nubot.pricefeeds.PriceFeedManager;
import com.nubits.nubot.tasks.SubmitLiquidityinfoTask;
import com.nubits.nubot.tasks.TaskManager;
import com.nubits.nubot.tasks.strategy.PriceMonitorTriggerTask;
import com.nubits.nubot.tasks.strategy.StrategySecondaryPegTask;
import com.nubits.nubot.trading.TradeInterface;
import com.nubits.nubot.trading.keys.ApiKeys;
import com.nubits.nubot.trading.wrappers.CcexWrapper;
import com.nubits.nubot.utils.FileSystem;
import com.nubits.nubot.utils.FrozenBalancesManager;
import com.nubits.nubot.utils.Utils;
import com.nubits.nubot.utils.logging.NuLogger;
import io.evanwong.oss.hipchat.v2.rooms.MessageColor;
import org.json.simple.JSONObject;

import java.io.IOException;
import java.util.logging.Logger;

/**
 * Provides the main class of NuBot. Instantiate this class to start the NuBot
 * program
 *
 * @author desrever <desrever at nubits.com>
 */
public class NuBot {

    private static final String USAGE_STRING = "java - jar NuBot <path/to/options.json> [path/to/options-part2.json] ... [path/to/options-partN.json]";
    private String logsFolder;
    private static Thread mainThread;
    private static final Logger LOG = Logger.getLogger(NuBot.class.getName());

    /**
     * Initialises the NuBot. Check if NuBot has valid parameters and quit if it
     * doesn't Check if NuBot is already running and Log if that is so
     *
     * @param args a list of valid arguments
     * @author desrever <desrever at nubits.com>
     */
    public static void main(String args[]) {
        mainThread = Thread.currentThread();

        NuBot app = new NuBot();

        Utils.printSeparator();
        if (app.isValidArgs(args)) {
            createShutDownHook();
            if (!Global.running) {
                app.execute(args);
            } else {
                LOG.severe("NuBot is already running. Make sure to terminate other instances.");
            }
        } else {
            LOG.severe("wrong argument number : run nubot with \n" + USAGE_STRING);
            System.exit(0);
        }
    }

    /**
     * @author desrever <desrever at nubits.com>
     */
    private void execute(String args[]) {
        Global.running = true;

        //Load settings
        Utils.loadProperties("settings.properties");

        //Load Options and test for critical configuration errors
        if (args.length > 1) {
            //more than one file path given
            try {
                Global.options = ParseOptions.parseOptions(args);
            } catch (NuBotConfigException ex) {
                Utils.exitWithMessage("NuBot wrongly configured");
                System.exit(0);
            }
        } else {
            try {
                Global.options = ParseOptions.parseOptionsSingle(args[0]);
            } catch (NuBotConfigException ex) {
                Utils.exitWithMessage("NuBot wrongly configured");
                System.exit(0);
            }
        }

        //test if configuration is supported
        if (!Utils.isSupported(Global.options.getPair())) {
            LOG.severe("This bot doesn't work yet with trading pair " + Global.options.getPair().toString());
            System.exit(0);
        }

        Utils.printSeparator();

        //Generate Bot Session unique id
        Global.sessionId = Utils.generateSessionID();
        LOG.info("Session ID = " + Global.sessionId);

        //Setting up log folder for this session :

        String folderName = "NuBot_" + Utils.getTimestampLong() + "_" + Global.options.getExchangeName() + "_" + Global.options.getPair().toString().toUpperCase() + "/";
        logsFolder = Global.settings.getProperty("log_path") + folderName;

        //Create log dir
        FileSystem.mkdir(logsFolder);
        try {
            NuLogger.setup(Global.options.isVerbose(), logsFolder);
        } catch (IOException ex) {
            LOG.severe(ex.toString());
        }
        LOG.info("Setting up  NuBot version : " + Global.settings.getProperty("version"));

        LOG.info("Init logging system");

        //Disable hipchat debug logging https://github.com/evanwong/hipchat-java/issues/16
        System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "error");

        LOG.info("Set up SSL certificates");
        boolean trustAllCertificates = false;
        if (Global.options.getExchangeName().equalsIgnoreCase(Constant.INTERNAL_EXCHANGE_PEATIO)) {
            trustAllCertificates = true;
        }
        Utils.installKeystore(trustAllCertificates);
        Utils.printSeparator();

        LOG.info("Wrap the keys into a new ApiKeys object");
        ApiKeys keys = new ApiKeys(Global.options.getApiSecret(), Global.options.getApiKey());
        Utils.printSeparator();


        LOG.info("Creating an Exchange object");

        Global.exchange = new Exchange(Global.options.getExchangeName());
        Utils.printSeparator();

        LOG.info("Create e ExchangeLiveData object to accommodate liveData from the exchange");
        ExchangeLiveData liveData = new ExchangeLiveData();
        Global.exchange.setLiveData(liveData);
        Utils.printSeparator();


        LOG.info("Create a new TradeInterface object");
        TradeInterface ti = Exchange.getTradeInterface(Global.options.getExchangeName());
        ti.setKeys(keys);
        ti.setExchange(Global.exchange);
        if (Global.options.getExchangeName().equals(Constant.CCEX)) {
            ((CcexWrapper) (ti)).initBaseUrl();
        }


        if (Global.options.getPair().getPaymentCurrency().equals(Constant.NBT)) {
            Global.swappedPair = true;
        } else {
            Global.swappedPair = false;
        }

        LOG.info("Swapped pair mode : " + Global.swappedPair);


        String apibase = "";
        if (Global.options.getExchangeName().equalsIgnoreCase(Constant.INTERNAL_EXCHANGE_PEATIO)) {
            ti.setApiBaseUrl(Constant.INTERNAL_EXCHANGE_PEATIO_API_BASE);
        }


        Global.exchange.setTrade(ti);
        Global.exchange.getLiveData().setUrlConnectionCheck(Global.exchange.getTrade().getUrlConnectionCheck());
        Utils.printSeparator();


        //For a 0 tx fee market, force a price-offset of 0.1%
        ApiResponse txFeeResponse = Global.exchange.getTrade().getTxFee(Global.options.getPair());
        if (txFeeResponse.isPositive()) {
            double txfee = (Double) txFeeResponse.getResponseObject();
            if (txfee == 0) {
                LOG.warning("The bot detected a 0 TX fee : forcing a priceOffset of 0.1% [if required]");
                if (Global.options.getSecondaryPegOptions().getSpread() < 0.1) {
                    Global.options.getSecondaryPegOptions().setSpread(0.1);
                }
            }
        }

        LOG.info("Create a TaskManager ");
        Global.taskManager = new TaskManager();
        Utils.printSeparator();

        if (Global.options.isSendRPC()) {
            LOG.info("Setting up (verbose) RPC client on " + Global.options.getNudIp() + ":" + Global.options.getNudPort());
            Global.publicAddress = Global.options.getNubitsAddress();
            Global.rpcClient = new NuRPCClient(Global.options.getNudIp(), Global.options.getNudPort(),
                    Global.options.getRpcUser(), Global.options.getRpcPass(), Global.options.isVerbose(), true,
                    Global.options.getNubitsAddress(), Global.options.getPair(), Global.options.getExchangeName());

            Utils.printSeparator();
            LOG.info("Starting task : Check connection with Nud");
            Global.taskManager.getCheckNudTask().start();
        }

        Utils.printSeparator();
        LOG.info("Starting task : Check connection with exchange");
        Global.taskManager.getCheckConnectionTask().start(1);


        Utils.printSeparator();
        LOG.info("Waiting  a for the connectionThreads to detect connection");
        try {
            Thread.sleep(3000);
        } catch (InterruptedException ex) {
            LOG.severe(ex.toString());
        }


        //Set the fileoutput for active orders

        String orders_outputPath = logsFolder + "orders_history.csv";
        String balances_outputPath = logsFolder + "balance_history.json";

        ((SubmitLiquidityinfoTask) (Global.taskManager.getSendLiquidityTask().getTask())).setOutputFiles(orders_outputPath, balances_outputPath);
        FileSystem.writeToFile("timestamp,activeOrders, sells,buys, digest\n", orders_outputPath, false);


        //Start task to check orders
        int start_delay = 40;
        Global.taskManager.getSendLiquidityTask().start(start_delay);

        Utils.printSeparator();

        if (Global.options.isSendRPC()) {
            Utils.printSeparator();
            LOG.info("Check connection with nud");
            if (Global.rpcClient.isConnected()) {
                LOG.info("RPC connection OK!");
            } else {
                LOG.severe("Problem while connecting with nud");
                System.exit(0);
            }
        }

        Utils.printSeparator();
        LOG.fine("Checking bot working mode");
        Global.isDualSide = Global.options.isDualSide();

        if (Global.options.isDualSide()) {
            LOG.info("Configuring NuBot for Dual-Side strategy");
        } else {
            LOG.info("Configuring NuBot for Sell-Side strategy");
        }

        Utils.printSeparator();


        //DANGER ZONE : This variable set to true will cause orders to execute
        Global.executeOrders = Global.options.isExecuteOrders();


        LOG.info("Start trading Strategy specific for " + Global.options.getPair().toString());

        LOG.info(Global.options.toStringNoKeys());


        // Set the frozen balance manager in the global variable
        Global.frozenBalances = new FrozenBalancesManager(Global.options.getExchangeName(), Global.options.getPair(), Global.settings.getProperty("frozen_folder"));

        //Switch strategy for different trading pair


        if (!Utils.requiresSecondaryPegStrategy(Global.options.getPair())) {
            Global.taskManager.getStrategyFiatTask().start(7);
        } else {

            SecondaryPegOptionsJSON cpo = Global.options.getSecondaryPegOptions();
            if (cpo == null) {
                LOG.severe("To run in secondary peg mode, you need to specify the crypto-peg-options");
                System.exit(0);
            }

            //Peg to a USD price via crypto pair
            Currency toTrackCurrency;

            if (Global.swappedPair) { //NBT as paymentCurrency
                toTrackCurrency = Global.options.getPair().getOrderCurrency();
            } else {
                toTrackCurrency = Global.options.getPair().getPaymentCurrency();
            }

            CurrencyPair toTrackCurrencyPair = new CurrencyPair(toTrackCurrency, Constant.USD);


            // set trading strategy to the price monitor task
            ((PriceMonitorTriggerTask) (Global.taskManager.getPriceTriggerTask().getTask()))
                    .setStrategy(((StrategySecondaryPegTask) (Global.taskManager.getSecondaryPegTask().getTask())));

            // set price monitor task to the strategy
            ((StrategySecondaryPegTask) (Global.taskManager.getSecondaryPegTask().getTask()))
                    .setPriceMonitorTask(((PriceMonitorTriggerTask) (Global.taskManager.getPriceTriggerTask().getTask())));

            // set liquidityinfo task to the strategy

            ((StrategySecondaryPegTask) (Global.taskManager.getSecondaryPegTask().getTask()))
                    .setSendLiquidityTask(((SubmitLiquidityinfoTask) (Global.taskManager.getSendLiquidityTask().getTask())));

            PriceFeedManager pfm = new PriceFeedManager(cpo.getMainFeed(), cpo.getBackupFeedNames(), toTrackCurrencyPair);
            //Then set the pfm
            ((PriceMonitorTriggerTask) (Global.taskManager.getPriceTriggerTask().getTask())).setPriceFeedManager(pfm);

            //Set the priceDistance threshold
            ((PriceMonitorTriggerTask) (Global.taskManager.getPriceTriggerTask().getTask())).setDistanceTreshold(cpo.getDistanceThreshold());

            //Set the wallet shift threshold
            ((PriceMonitorTriggerTask) (Global.taskManager.getPriceTriggerTask().getTask())).setWallchangeThreshold(cpo.getWallchangeThreshold());

            //Set the outputpath for wallshifts

            String outputPath = logsFolder + "wall_shifts.csv";
            ((PriceMonitorTriggerTask) (Global.taskManager.getPriceTriggerTask().getTask())).setOutputPath(outputPath);
            FileSystem.writeToFile("timestamp,source,crypto,price,currency,sellprice,buyprice,otherfeeds\n", outputPath, false);

            //read the delay to sync with remote clock
            //issue 136 - multi custodians on a pair.
            //walls are removed and re-added every three minutes.
            //Bot needs to wait for next 3 min window before placing walls
            //set the interval from settings

            //TODO set in NuBotoptions, or subclass
            int reset_every = NuBotOptionsDefault.reset_every;
            int refresh_time_seconds = NuBotOptionsDefault.refresh_time_seconds;

            int interval = 1;
            if (!Global.options.isMultipleCustodians()) {
                interval = refresh_time_seconds;
            } else {
                interval = 60 * reset_every;
                //Force the a spread to avoid collisions
                double forcedSpread = 0.9;
                LOG.info("Forcing a " + forcedSpread + "% minimum spread to protect from collisions");
                if (Global.options.getSecondaryPegOptions().getSpread() < forcedSpread) {
                    Global.options.getSecondaryPegOptions().setSpread(forcedSpread);
                }
            }

            Global.taskManager.getPriceTriggerTask().setInterval(interval);

            int delaySeconds = 0;

            if (Global.options.isMultipleCustodians()) {
                delaySeconds = Utils.getSecondsToNextwindow(reset_every);
                LOG.info("NuBot will be start running in " + delaySeconds + " seconds, to sync with remote NTP and place walls during next wall shift window.");
            } else {
                LOG.warning("NuBot will not try to sync with other bots via remote NTP : 'multiple-custodians' is set to false");
            }
            //then start the thread
            Global.taskManager.getPriceTriggerTask().start(delaySeconds);
        }


        notifyOnline();
    }

    private static void notifyOnline() {
        String mode = "sell-side";
        if (Global.options.isDualSide()) {
            mode = "dual-side";
        }
        String msg = "A new <strong>" + mode + "</strong> bot just came online on " + Global.options.getExchangeName() + " pair (" + Global.options.getPair().toString("_") + ")";
        HipChatNotifications.sendMessage(msg, MessageColor.GREEN);
    }

    /**
     * check if arguments to NuBot are valid supported are arguments larger then
     * 0
     *
     * @param args
     * @return
     */
    private boolean isValidArgs(String[] args) {
        boolean valid = args.length > 0;

        return valid;
    }

    private static void createShutDownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override
            public void run() {

                LOG.info("Bot shutting down..");

                if (Global.options != null) {
                    //Try to cancel all orders, if any
                    if (Global.exchange.getTrade() != null && Global.options.getPair() != null) {
                        LOG.info("Clearing out active orders ... ");

                        ApiResponse deleteOrdersResponse = Global.exchange.getTrade().clearOrders(Global.options.getPair());
                        if (deleteOrdersResponse.isPositive()) {
                            boolean deleted = (boolean) deleteOrdersResponse.getResponseObject();

                            if (deleted) {
                                LOG.info("Order clear request succesfully");
                            } else {
                                LOG.severe("Could not submit request to clear orders");
                            }

                        } else {
                            LOG.severe(deleteOrdersResponse.getError().toString());
                        }
                    }

                    //reset liquidity info
                    if (Global.rpcClient.isConnected() && Global.options.isSendRPC()) {
                        //tier 1
                        LOG.info("Resetting Liquidity Info before quit");

                        JSONObject responseObject1 = Global.rpcClient.submitLiquidityInfo(Global.rpcClient.USDchar,
                                0, 0, 1);
                        if (null == responseObject1) {
                            LOG.severe("Something went wrong while sending liquidityinfo");
                        } else {
                            LOG.fine(responseObject1.toJSONString());
                        }

                        JSONObject responseObject2 = Global.rpcClient.submitLiquidityInfo(Global.rpcClient.USDchar,
                                0, 0, 2);
                        if (null == responseObject2) {
                            LOG.severe("Something went wrong while sending liquidityinfo");
                        } else {
                            LOG.fine(responseObject2.toJSONString());
                        }
                    }

                    LOG.info("Exit. ");
                    NuBot.mainThread.interrupt();
                    if (Global.taskManager != null) {
                        if (Global.taskManager.isInitialized()) {
                            Global.taskManager.stopAll();
                        }
                    }
                }

                Thread.currentThread().interrupt();
                return;
            }
        }));
    }
}
