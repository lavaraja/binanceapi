#Binance API

There are around 300 currencies available on binance platform.

Setup:

Import the source code in eclipse as maven project.

Build the project.

Create a file named initialconfig.properties and add the below information.

host="localhost"
API-KEY=""
SECRET=""

Create another file symbols.info with list of currencies. 

#Install redis database. 

install redis on the localhost using apt or yum based the linux distro.

apt install redis-server

check the server status. If not running start manually.

service redis-server status
sudo service redis-server start

Try connecting to redis :

type > redis-cli ping

you should see a success message as below. 

lavaraja@lavaraja-Lenovo-G580:~/Downloads/binance-java-api-master$ redis-cli ping
PONG

Now run the binanceapi.java file.The api services will run in the backend. we can the orderupdates happening for each currency we 
specfied in the list. Also the order data is stored in redis as sorted sets for each symbol.

Open console and connect to redis-cli.Run "keys *" to confirm that our order is stored in redis.

lavaraja@lavaraja-Lenovo-G580:~/Downloads/binance-java-api-master$ redis-cli
127.0.0.1:6379> keys *
  1) "BTCTUSD:bids"
  2) "FTMBTC:bids"
  3) "FUNBTC:asks"
  4) "BTCBBTC:bids"
  5) "WABIBTC:bids"
  6) "DENTBTC:bids"
  7) "YOYOBTC:bids"
  8) "EDOBTC:bids"
  9) "ADABTC:bids"
 10) "RVNBTC:bids"
-------------------
-------------------
---------------------

Fetch first n orders :
we are stroing updatedid as timeseries key and storing it in sorted set in redis.
Time complexity: O(log(N)) for each item added, where N is the number of elements in the sorted set.
 
 Get n recent orders for a stock :
 
 127.0.0.1:6379> ZREVRANGE ALGOBTC:asks 0 20 WITHSCORES
 1) "0.00008050:1284.38000000"
 2) "12727052"
 3) "0.00008040:169.77000000"
 4) "12727052"
 5) "0.00008030:383.29000000"
 6) "12727052"
 7) "0.00008020:902.06000000"
 8) "12727052"
 9) "0.00008010:648.92000000"
10) "12727052"
11) "0.00008000:1028.46000000"
12) "12727052"
13) "0.00007990:2548.70000000"
14) "12727052"
15) "0.00007980:2030.24000000"
16) "12727052"
17) "0.00007970:248.53000000"
18) "12727052"
19) "0.00007960:60.50000000"
20) "12727052"
21) "0.00008050:944.58000000"
22) "12726476"

127.0.0.1:6379> ZREVRANGE ALGOBTC:bids 0 20 WITHSCORES
 1) "0.00007940:69.73000000"
 2) "12727052"
 3) "0.00007930:2241.34000000"
 4) "12727052"
 5) "0.00007920:1799.50000000"
 6) "12727052"
 7) "0.00007910:2309.68000000"
 8) "12727052"
 9) "0.00007890:316.21000000"
10) "12727052"
11) "0.00007880:2023.21000000"
12) "12727052"
13) "0.00007870:1315.46000000"
14) "12727052"
15) "0.00007860:26812.52000000"
16) "12727052"
17) "0.00007850:5138.35000000"


we could see most recent asks/bids data is returned with timestamp.The same can be pushed using 
redis publish/subscribe model to display it in the webpage. 

