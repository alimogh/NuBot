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
package com.nubits.nubot.tasks.strategy;

import com.nubits.nubot.global.Global;
import com.nubits.nubot.notifications.HipChatNotifications;
import com.nubits.nubot.notifications.jhipchat.messages.Message.Color;
import com.nubits.nubot.tasks.SubmitLiquidityinfoTask;
import java.util.TimerTask;
import java.util.logging.Logger;

/**
 *
 * @author desrever <desrever at nubits.com>
 */
public class StrategySecondaryPegTask extends TimerTask {

    private static final Logger LOG = Logger.getLogger(StrategySecondaryPegTask.class.getName());
    private SecondaryPegStrategyUtils strategyUtils = new SecondaryPegStrategyUtils(this);
    private boolean mightNeedInit = true;
    private int activeSellOrders, activeBuyOrders, totalActiveOrders;
    private boolean ordersAndBalancesOK;
    private boolean needWallShift;
    private double sellPricePEG;
    private double buyPricePEG;
    private String priceDirection;  //this parameter can be either Constant.UP (when the price of the new order increased since last wall) or Constant.DOWN
    private PriceMonitorTriggerTask priceMonitorTask;
    private SubmitLiquidityinfoTask sendLiquidityTask;
    private boolean isFirstTime = true;
    private boolean proceedsInBalance = false; // Only used on secondary peg to fiat (EUR , CNY etc)

    @Override
    public void run() {
        LOG.fine("Executing task on " + Global.exchange.getName() + ": StrategySecondaryPegTask. DualSide :  " + Global.options.isDualSide());

        if (!isFirstTime) {

            strategyUtils.recount(); //Count number of active sells and buys
            boolean shiftSuccess = false;
            if (needWallShift) {
                String message = "Shift needed on " + Global.exchange.getName() + ": " + Global.options.getPair().getPaymentCurrency().getCode().toUpperCase() + " "
                        + "price went " + getPriceDirection() + " more than " + Global.options.getSecondaryPegOptions().getWallchangeThreshold() + " %";
                HipChatNotifications.sendMessage(message, Color.PURPLE);
                LOG.warning(message);

                shiftSuccess = strategyUtils.shiftWalls();
                if (shiftSuccess) {
                    mightNeedInit = false;
                    needWallShift = false;
                    LOG.info("Wall shift successful");


                } else {
                    LOG.severe("Wall shift failed");
                }
                strategyUtils.recount();
            }

            if (mightNeedInit) {
                boolean reset = mightNeedInit && !(ordersAndBalancesOK);
                if (reset) {
                    String message = "Order reset needed on " + Global.exchange.getName();
                    HipChatNotifications.sendMessage(message, Color.PURPLE);
                    LOG.warning(message);
                    boolean reinitiateSuccess = strategyUtils.reInitiateOrders(false);
                    if (reinitiateSuccess) {
                        mightNeedInit = false;
                    }
                } else {
                    LOG.fine("No need to init new orders since current orders seems correct");
                }
                strategyUtils.recount();
            }

            //Make sure the orders and balances are ok or try to aggregate
            if (!ordersAndBalancesOK) {
                LOG.severe("Detected a number of active orders not in line with strategy. Will try to aggregate soon");
                mightNeedInit = true;
            } else {
                if (Global.options.getKeepProceeds() > 0 && Global.options.getPair().getPaymentCurrency().isFiat()) {
                    //Execute buy Side strategy
                    if (Global.isDualSide && proceedsInBalance && !needWallShift) {
                        strategyUtils.aggregateAndKeepProceeds();
                    }
                }
            }
        } else //First execution : reset orders and init strategy
        {
            LOG.info("Initializing strategy");
            isFirstTime = false;
            strategyUtils.recount();
            boolean reinitiateSuccess = strategyUtils.reInitiateOrders(true);
            if (!reinitiateSuccess) {
                LOG.severe("There was a problem while trying to reinitiating orders on first execution. Trying again on next execution");
                isFirstTime = true;
            }
        }
    }

    public void notifyPriceChanged(double new_sellPricePEG, double new_buyPricePEG, double conversion, String direction) {
        LOG.warning("Strategy received a price change notification.");
        needWallShift = true;
        Global.conversion = conversion;
        if (!Global.swappedPair) {
            sellPricePEG = new_sellPricePEG;
            buyPricePEG = new_buyPricePEG;
        } else {
            sellPricePEG = new_buyPricePEG;
            buyPricePEG = new_sellPricePEG;
        }
        this.priceDirection = direction;
    }

    //Getters and setters ----------------------------------------
    public double getSellPricePEG() {
        return sellPricePEG;
    }

    public void setSellPricePEG(double sellPricePEG) {
        this.sellPricePEG = sellPricePEG;
    }

    public double getBuyPricePEG() {
        return buyPricePEG;
    }

    public void setBuyPricePEG(double buyPricePEG) {
        this.buyPricePEG = buyPricePEG;
    }

    public PriceMonitorTriggerTask getPriceMonitorTask() {
        return priceMonitorTask;
    }

    public void setPriceMonitorTask(PriceMonitorTriggerTask priceMonitorTask) {
        this.priceMonitorTask = priceMonitorTask;
    }

    public SubmitLiquidityinfoTask getSendLiquidityTask() {
        return sendLiquidityTask;
    }

    public void setSendLiquidityTask(SubmitLiquidityinfoTask sendLiquidityTask) {
        this.sendLiquidityTask = sendLiquidityTask;
    }

    public int getActiveSellOrders() {
        return activeSellOrders;
    }

    public void setActiveSellOrders(int activeSellOrders) {
        this.activeSellOrders = activeSellOrders;
    }

    public int getActiveBuyOrders() {
        return activeBuyOrders;
    }

    public void setActiveBuyOrders(int activeBuyOrders) {
        this.activeBuyOrders = activeBuyOrders;
    }

    public int getTotalActiveOrders() {
        return totalActiveOrders;
    }

    public void setTotalActiveOrders(int totalActiveOrders) {
        this.totalActiveOrders = totalActiveOrders;
    }

    public boolean isMightNeedInit() {
        return mightNeedInit;
    }

    public void setMightNeedInit(boolean mightNeedInit) {
        this.mightNeedInit = mightNeedInit;
    }

    public boolean isOrdersAndBalancesOK() {
        return ordersAndBalancesOK;
    }

    public void setOrdersAndBalancesOK(boolean ordersAndBalancesOK) {
        this.ordersAndBalancesOK = ordersAndBalancesOK;
    }

    public boolean isFirstTime() {
        return isFirstTime;
    }

    public void setIsFirstTime(boolean isFirstTime) {
        this.isFirstTime = isFirstTime;
    }

    public boolean isProceedsInBalance() {
        return proceedsInBalance;
    }

    public void setProceedsInBalance(boolean proceedsInBalance) {
        this.proceedsInBalance = proceedsInBalance;
    }

    public String getPriceDirection() {
        return priceDirection;
    }

    public void setPriceDirection(String priceDirection) {
        this.priceDirection = priceDirection;
    }
}
