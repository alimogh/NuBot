package com.nubits.nubot.strategy;


import com.nubits.nubot.bot.Global;
import com.nubits.nubot.models.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * BalanceManager channels balances queries to exchanges and stores the results
 * gets triggered from tasks
 */
public class BalanceManager {

    private static final Logger LOG = LoggerFactory.getLogger(BalanceManager.class.getName());

    private Amount balance;

    private PairBalance pairBalance;

    private long lastFetchBalance, lastFetchPairBalance;

    /*public void fetchBalance(Currency currency) throws Exception {
        ApiResponse balancesResponse = Global.exchange.getTrade().getAvailableBalance(currency);
        if (!balancesResponse.isPositive()) {
            this.lastFetchBalance = System.currentTimeMillis();
            String errmsg = balancesResponse.getError().toString();
            LOG.error(errmsg);
            throw new Exception(errmsg);
        }

        this.balance = (Amount) balancesResponse.getResponseObject();

    }*/


    public void fetchBalances(CurrencyPair pair) throws Exception {
        ApiResponse balancesResponse = Global.exchange.getTrade().getAvailableBalances(pair);

        if (!balancesResponse.isPositive()) {
            this.lastFetchPairBalance = System.currentTimeMillis();
            String errmsg =balancesResponse.getError().toString();
            LOG.error(errmsg);
            throw new Exception(errmsg);
        }


        this.pairBalance = (PairBalance) balancesResponse.getResponseObject();

    }

    public void fetchBalancePairTimeBound(CurrencyPair pair, double tresh) throws Exception {
        long current = System.currentTimeMillis();
        long diff = current - this.lastFetchPairBalance;
        LOG.debug("balance. diff: " + diff);
        if (diff > tresh){
            LOG.debug("triggered fetch");
            fetchBalances(pair);
        }
    }


    public Amount getBalance() {
        return this.balance;
    }

    public PairBalance getPairBalance(){
        return this.pairBalance;
    }
}
