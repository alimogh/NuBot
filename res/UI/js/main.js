  var host = "localhost";
  var port = 4567;
  var baseurl = "http://" + host + ":" + port;
  var updateTime = 500;
  var debug = true;

  var dotCounter = 1 ;
  var botRunning = false;

  var logLine = 0;

function toggleBot(running)
{
    if(running)
    {
      botRunning = true;
      $('#togglebot').html("Stop Bot");
      $('#togglebot').removeClass("btn-primary");
      $('#togglebot').addClass("btn-warning");

      //$('#status-img').attr("src","img/bot_running.gif");

      document.title = 'NuBot GUI - Running'+animateDots();
      //$("#favicon").attr("href","favicon-running.ico");
    }
    else
    {
      botRunning = false;
      updateFavico(0);
      $('#togglebot').html("Start Bot");

      $('#togglebot').removeClass("btn-warning");
      $('#togglebot').addClass("btn-primary");

      //$('#status-img').attr("src","img/bot_running.png");

      $("#ordertable").find("tr:gt(0)").remove();
      $("#balancetable").find("tr:gt(0)").remove();
      document.title = 'NuBot GUI - Stopped';
      //$("#favicon").attr("href","favicon.ico");
    }
}

function updateNavbar(page) {
    $('#operation-nav').removeClass('active');
    $('#config-nav').removeClass('active');
    $('#docu-nav').removeClass('active');

    switch (page) {
        case "operation":
            $('#operation-nav').addClass('active')
            loadbot();
            break;
        case "config":
            loadconfig();
             $('#config-nav').addClass('active')
            break;
        case "docu":
            $('#docu-nav').addClass('active')
            break;
        case "disclaimer":
            $('#disclaimer-nav').addClass('active')
        break;
    }
 }

function updateConfigFile(){
         $.ajax({
          type: "GET",
          dataType: "json",
          url:  "http://" + host + ":" + port + "/configfile"
        })
        .fail(function() {
          console.log("error loading configfile");
        })
        .done(function( data ) {
            //console.log("config data: " + data);
            $('#configfile').html(data["configfile"]);
        });
}

function updateOrders(){
     $.ajax({
      type: "GET",
      dataType: "json",
      url:  "http://" + host + ":" + port + "/info"
    })
    .fail(function() {
      console.log("error loading info");
    })
    .done(function( data ) {

        //console.log("buys: " + data["buys"]);
        console.log("BuyCurrency: " + data["BuyCurrency"]);
        console.log("SellCurrency: " + data["SellCurrency"]);

        if (data.hasOwnProperty("BuyCurrency")) {
                $("#balancetable").find("tr:gt(0)").remove();

                var qty = data["BuyCurrency"]["quantity"];
                var cry = data["BuyCurrency"]["currency"]["code"];

                var rowhtml = '<tr><td>' + qty + '</td><td>' + cry + '</td></tr>';
                $("#balancetable").find('tbody').after(rowhtml);

                qty = data["SellCurrency"]["quantity"];
                cry = data["SellCurrency"]["currency"]["code"];
                rowhtml = '<tr><td>' + qty + '</td><td>' + cry + '</td></tr>';
                $("#balancetable").find('tbody').after(rowhtml);
        }

        if (data.hasOwnProperty("orders")) {

             $("#ordertable").find("tr:gt(0)").remove();

            var orders = data["orders"];
            updateFavico(orders.length);
            for (var i = 0; i < orders.length; i++){
                var order = orders[i];
                var type = order["type"];
                var qty = order["amount"]["quantity"];
                var price = order["price"]["quantity"];
                var rowhtml = '<tr><td>' + type + '</td><td>' + qty + '</td><td>' + price + '</td></tr>';
                $("#ordertable").find('tbody').after(rowhtml);
            }
        }

    });
    setTimeout(updateOrders , 2000);
 }

function updateStatus(){
         $.ajax({
          type: "GET",
          dataType: "json",
          url:  "http://" + host + ":" + port + "/opstatus"
        })
        .fail(function() {

        })
        .done(function( data ) {
            //console.log("status data: " + JSON.parse(JSON.stringify(data)));

            $('#botstatus').html(data["status"]);

            $('#sessionstart').html(data["sessionstart"]);

            $('#duration').html(data["duration"]);

            if (data["status"] == "running"){
                toggleBot(true);
            }

        });

        //re-call the function
        setTimeout(updateStatus, updateTime);
}

function flashButton(cbtn){

    if (cbtn.hasClass("btn-primary")) {
       cbtn.removeClass("btn-primary");
       cbtn.addClass("btn-success");

       //change color back after 2000 msec
       setTimeout(function() {
             cbtn.removeClass("btn-success");
             cbtn.addClass("btn-primary");
       }, 2000);
    }

}

function flashButtonRed(cbtn){

    if (cbtn.hasClass("btn-primary")) {
       cbtn.removeClass("btn-primary");
       cbtn.addClass("btn-danger");

       //change color back after 2000 msec
       setTimeout(function() {
             cbtn.removeClass("btn-danger");
             cbtn.addClass("btn-primary");
       }, 2000);
    }

}

function startBot(){
    if(confirm("Are you sure you want to start the bot?")){
        if (debug) console.log("calling start on server");

        var jsondata = JSON.stringify({
            "operation": "start"});

        $.ajax(baseurl + "/startstop", {
                data : jsondata,
                dataType: "json",
                type : 'POST',
                success: function(data){
                    console.log("starting bot ok " + data);
                    var success = data["success"];

                    if (success){
                     //on success of post change the color of the button
                      toggleBot(true);
                    } else{
                        flashButtonRed(cbtn);
                        alert(data["error"]);
                    }

                },
                error: function(XMLHttpRequest, textStatus, errorThrown) {
                    alert("error posting to server " + textStatus + " " + errorThrown);
                }
           });
         }
}

function stopBot()
{
   if (debug) console.log("calling stop on server");
      if(confirm("Are you sure you want to stop the bot?"))
      {
         var jsondata = JSON.stringify({
           "operation": "stop"});

         $.ajax(baseurl + "/startstop", {
               data : jsondata,
               dataType: "json",
               type : 'POST',
               success: function(data){
                  console.log("stopping bot " + data);
                  var success = data["success"];
                   var cbtn = $('#togglebot');
                  if (success){
                   //on success of post change the color of the button
                    flashButton(cbtn);
                    $('#duration').html("");
                    toggleBot(false);
                  } else{
                      alert(data["error"]);
                      flashButtonRed(cbtn);
                  }
               },
               error: function(XMLHttpRequest, textStatus, errorThrown) {
                   alert("error posting to server " + textStatus + " " + errorThrown);
               }
          });
      }
}

$(document).ready(function() {

    updateConfigFile();
    updateOrders();


    $('#togglebot').click(function()
      {
        if(botRunning)
            stopBot();
        else
            startBot();
     });
}); //end document.ready function

function autoScroll(){

        //autoscrolling. not used - how to make log readable also?
        var psconsole = $('#logarea');
        if(psconsole.length)
          psconsole.scrollTop(psconsole[0].scrollHeight - psconsole.height());

    }

function updateLog() {

    var url = baseurl + "/logdump";

    console.log("url  " + url);
    $.ajax({
      type: "GET",
      dataType: "json",
      url: url,
    })
    .fail(function() {
      console.log("error loading log");
    })
    .done(function( data ) {
      $('#logarea').val(data["log"]);
    });

    logLine++;

    if (logLine % 20 == 0) {
        autoScroll();
    }

    setTimeout(updateLog , updateTime);
}

function loadbot() {
        updateLog();
        updateStatus();
    }

function loadconfig() {

        var url = "http://" + host + ":" + port + "/config";

        $.ajax({
          type: "GET",
          dataType: "json",
          url: url,
        })
        .done(function( data ) {

            $("#exchangename").attr("value", data["exchangeName"]);
            $("#apikey").attr("value", data["apiKey"]);
            $("#apisecret").attr("value", data["apiSecret"]);
            $("#txfee").attr("value", data["txFee"]);
            $("#pair").attr("value", data["pair"]);
            $("#dualside").attr("checked", data["dualSide"]);
            $("#multiplecustodians").attr("checked", data["multipleCustodians"]);
            $("#executeorders").attr("checked", data["executeOrders"]);
            $("#verbose").attr("checked", data["verbose"]);
            $("#hipchat").attr("checked", data["hipchat"]);
            $("#mailnotifications").attr("value", data["mailnotifications"]);
            $("#mailrecipient").attr("value", data["mailRecipient"]);
            $("#emergencytimeout").attr("value", data["emergencyTimeout"]);
            $("#keepproceeds").attr("value", data["keepProceeds"]);
            $("#maxsellvolume").attr("value", data["maxSellVolume"]);
            $("#maxbuyvolume").attr("value", data["maxBuyVolume"]);
            $("#priceincrement").attr("value", data["priceIncrement"]);
            $("#submitliquidity").attr("checked", data["submitliquidity"]);
            $("#nubitaddress").attr("value", data["nubitAddress"]);
            $("#nudip").attr("value", data["nudIp"]);
            $("#nudport").attr("value", data["nudPort"]);
            $("#rpcpass").attr("value", data["rpcPass"]);
            $("#rpcuser").attr("value", data["rpcUser"]);
            $("#wallchangethreshold").attr("value", data["wallchangeThreshold"]);
            $("#spread").attr("value", data["spread"]);
            $("#mainfeed").attr("value", data["mainFeed"]);
            $("#backupfeeds").attr("value", data["backupFeeds"]);

         });
    }

function postall(){

        var posturl = "http://" + host + ":" + port + "/config";

        //get all vars and post them

        var exchangename = $("#exchangename").attr("value");
        var apikey = $("#apikey").attr("value");
        var apisecret = $("#apisecret").attr("value");
        var txfee = $("#txfee").attr("value");
        var pair = $("#pair").attr("value");
        var dualside = $("#dualside").prop("checked");
        var multiplecustodians = $("#multiplecustodians").prop("checked");
        var executeorders = $("#executeorders").prop("checked");
        var verbose = $("#verbose").prop("checked");
        var hipchat = $("#hipchat").prop("checked");
        var mailnotifications = $("#mailnotifications").attr("value");
        var mailrecipient = $("#mailrecipient").attr("value");
        var emergencytimeout = $("#emergencytimeout").attr("value");
        var keepproceeds = $("#keepproceeds").attr("value");
        var maxsellvolume = $("#maxsellvolume").attr("value");
        var maxbuyvolume = $("#maxbuyvolume").attr("value");
        var priceincrement = $("#priceincrement").attr("value");
        var submitliquidity = $("#submitliquidity").prop("checked");
        var nubitaddress = $("#nubitaddress").attr("value");
        var nudip = $("#nudip").attr("value");
        var nudport = $("#nudport").attr("value");
        var rpcpass = $("#rpcpass").attr("value");
        var rpcuser = $("#rpcuser").attr("value");
        var wallchangethreshold = $("#wallchangethreshold").attr("value");
        var spread = $("#spread").attr("value");
        var mainfeed = $("#mainfeed").attr("value");

        var backupfeedsString = $("#backupfeeds").attr("value");
        var backupfeedsarray = backupfeedsString.split(',');

        //could check on the client side if valid post

        var jsondata = JSON.stringify({
            "exchangename": exchangename,
            "apikey": apikey,
            "apisecret": apisecret,
            "txfee" : txfee,
            "pair": pair,
            "dualside": dualside,
            "multiplecustodians": multiplecustodians,
            "executeorders": executeorders,
            "verbose": verbose,
            "hipchat": hipchat,
            "mailrecipient": mailrecipient,
            "mailnotifications": mailnotifications,
            "emergencytimeout" : emergencytimeout,
            "keepproceeds" : keepproceeds,
            "maxsellvolume": maxsellvolume,
            "maxbuyvolume": maxbuyvolume,
            "priceincrement": priceincrement,
            "submitliquidity": submitliquidity,
            "nubitaddress": nubitaddress,
            "nudip": nudip,
            "nudport": nudport,
            "rpcpass": rpcpass,
            "rpcuser": rpcuser,
            "wallchangethreshold": wallchangethreshold,
            "spread" : spread,
            "mainfeed": mainfeed,
            "backupfeeds" : backupfeedsarray

        });

        makePost(posturl, jsondata);
    }

function makePost(url, jsondata){
        //make HTTP post to server

        $.ajax(url, {
            data : jsondata,
            dataType: "json",
            type : 'POST',
            success: function(data) {

             console.log("got data " + data);

             // server returns custom error message
             var success = data["success"];
             console.log("success " + success );
             var errormsg = data["error"];
             console.log("error " + errormsg);

             if (success){

                 //on success of post change the color of the button
                  var cbtn = $('#saveconfigbutton');
                  flashButton(cbtn);

              } else{
                  alert(errormsg);
              }

            },

            error: function(xhr, textStatus, errorThrown) {
                alert("error posting to server " + textStatus + " " + errorThrown);
                alert(xhr.responseText);
            }
       });
    }

function postreset(){
    if(confirm("Are you sure?")){
    var posturl = "http://" + host + ":" + port + "/configreset";

    $.ajax(posturl, {

        contentType : 'application/json',
        type : 'POST',
        success: function(data){

          //on success of post change the color of the button
          var rbtn = $('#resetbutton');

          if (rbtn.hasClass("btn-primary")) {
               rbtn.removeClass("btn-primary");
               rbtn.addClass("btn-success");

               //change color back after 2000 msec
               setTimeout(function() {
                     rbtn.removeClass("btn-success");
                     rbtn.addClass("btn-primary");
               }, 2000);
          }

          //reset values from server
          updateConfigFile();
          loadconfig();
        },

        error: function(xhr, textStatus, errorThrown) {
            alert("error posting to server " + textStatus + " " + errorThrown);
            alert(xhr.responseText);
        }
   });
 }
}

function updateIframe(pageName)
{
         $('#docu-iframe').attr('src', 'docs/'+pageName+'.html');
}

function updateFavico(howmany)
{
    var favicon = new Favico({
        animation : 'popFade'
    });

    //intial value
    favicon.badge(howmany);
 }

 function animateDots()
 {
     toReturn = "";
     dotCounter++;
     if(dotCounter>4)
     {
         dotCounter=1;
     }
     for(i=0; i<dotCounter; i++)
         toReturn+='.';
     return toReturn;
 }