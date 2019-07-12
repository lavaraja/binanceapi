import com.binance.api.*;
import com.binance.api.client.*;
import com.binance.api.client.impl.BinanceApiAsyncRestClientImpl;
import com.binance.api.client.impl.BinanceApiRestClientImpl;
import com.binance.api.client.impl.BinanceApiWebSocketClientImpl;

import com.binance.api.client.domain.event.DepthEvent;
import com.binance.api.client.domain.market.OrderBook;
import com.binance.api.client.domain.market.OrderBookEntry;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.io.*;
import java.math.BigDecimal;
import java.net.URL;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Properties;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.Transaction;


public class binanceapi {


	  private static final String BIDS = "BIDS";
	  private static final String ASKS = "ASKS";
	  	
	  private final String symbol;
	  private final BinanceApiRestClient restClient;
	  private final BinanceApiWebSocketClient wsClient;
	  private final WsCallback wsCallback = new WsCallback();
	  private final Map<String, NavigableMap<BigDecimal, BigDecimal>> depthCache = new HashMap<>();

	  private long lastUpdateId = -1;
	  private volatile Closeable webSocket;

	  public binanceapi(String symbol) {
	    this.symbol = symbol;
	    System.out.println("Running for Symbol "+symbol);
	    
	    BinanceApiClientFactory factory = BinanceApiClientFactory.newInstance();
	    this.wsClient = factory.newWebSocketClient();
	    this.restClient = factory.newRestClient();

	    initialize();
	  }

	  private void initialize() {
		  System.out.println("Getting data for "+this.symbol);
	    // 1. Subscribe to depth events and cache any events that are received.
	    final List<DepthEvent> pendingDeltas = startDepthEventStreaming();

	    // 2. Get a snapshot from the rest endpoint and use it to build your initial depth cache.
	    initializeDepthCache();

	    // 3. & 4. handled in here.
	    applyPendingDeltas(pendingDeltas);
	  }

	  /**
	   * Begins streaming of depth events.
	   *
	   * Any events received are cached until the rest API is polled for an initial snapshot.
	   */
	  private List<DepthEvent> startDepthEventStreaming() {
	    final List<DepthEvent> pendingDeltas = new CopyOnWriteArrayList<>();
	    wsCallback.setHandler(pendingDeltas::add);

	    this.webSocket = wsClient.onDepthEvent(symbol.toLowerCase(), wsCallback);

	    return pendingDeltas;
	  }

	  /**
	   * 2. Initializes the depth cache by getting a snapshot from the REST API.
	   */
	  
	  /**
	   * Store initial depth cache in redis sorted sets.The sort key will be our 
	   * updated id. Keyname will be symbol
	   * 
	   */
	  private void initializeDepthCache() {
	    OrderBook orderBook = restClient.getOrderBook(symbol.toUpperCase(), 10);
	    Jedis jedis=new Jedis("localhost");
	    this.lastUpdateId = orderBook.getLastUpdateId();
	    
	    NavigableMap<BigDecimal, BigDecimal> asks = new TreeMap<>(Comparator.reverseOrder());
	
	    for (OrderBookEntry ask : orderBook.getAsks()) {
	    	jedis.zadd(symbol.toUpperCase()+":asks",this.lastUpdateId,ask.getPrice()+":"+ask.getQty());
	    	
	      asks.put(new BigDecimal(ask.getPrice()), new BigDecimal(ask.getQty()));
	    }
	   
	    depthCache.put(ASKS, asks);
	    
	 
	    NavigableMap<BigDecimal, BigDecimal> bids = new TreeMap<>(Comparator.reverseOrder());
	    for (OrderBookEntry bid : orderBook.getBids()) {
	    	jedis.zadd(symbol.toUpperCase()+":bids",this.lastUpdateId,bid.getPrice()+":"+bid.getQty());
	      bids.put(new BigDecimal(bid.getPrice()), new BigDecimal(bid.getQty()));
	    }
	    
	    depthCache.put(BIDS, bids);
	  }

	  /**
	   * Deal with any cached updates and switch to normal running.
	   */
	  private void applyPendingDeltas(final List<DepthEvent> pendingDeltas) {
	    final Consumer<DepthEvent> updateOrderBook = newEvent -> {
	      if (newEvent.getFinalUpdateId() > lastUpdateId) {
	        System.out.println(newEvent);
	        lastUpdateId = newEvent.getFinalUpdateId();
	        updateOrderBook(getAsks(), newEvent.getAsks());
	        updateOrderBook(getBids(), newEvent.getBids());
	        printDepthCache();
	      }
	    };

	    final Consumer<DepthEvent> drainPending = newEvent -> {
	      pendingDeltas.add(newEvent);

	      // 3. Apply any deltas received on the web socket that have an update-id indicating they come
	      // after the snapshot.
	      pendingDeltas.stream()
	          .filter(
	              e -> e.getFinalUpdateId() > lastUpdateId) // Ignore any updates before the snapshot
	          .forEach(updateOrderBook);

	      // 4. Start applying any newly received depth events to the depth cache.
	      wsCallback.setHandler(updateOrderBook);
	    };

	    wsCallback.setHandler(drainPending);
	  }

	  /**
	   * Updates an order book (bids or asks) with a delta received from the server.
	   *
	   * Whenever the qty specified is ZERO, it means the price should was removed from the order book.
	   */
	  private void updateOrderBook(NavigableMap<BigDecimal, BigDecimal> lastOrderBookEntries,
	                               List<OrderBookEntry> orderBookDeltas) {
	    for (OrderBookEntry orderBookDelta : orderBookDeltas) {
	      BigDecimal price = new BigDecimal(orderBookDelta.getPrice());
	      BigDecimal qty = new BigDecimal(orderBookDelta.getQty());
	      if (qty.compareTo(BigDecimal.ZERO) == 0) {
	        // qty=0 means remove this level
	        lastOrderBookEntries.remove(price);
	      } else {
	        lastOrderBookEntries.put(price, qty);
	      }
	    }
	  }

	  public NavigableMap<BigDecimal, BigDecimal> getAsks() {
	    return depthCache.get(ASKS);
	  }

	  public NavigableMap<BigDecimal, BigDecimal> getBids() {
	    return depthCache.get(BIDS);
	  }

	  /**
	   * @return the best ask in the order book
	   */
	  private Map.Entry<BigDecimal, BigDecimal> getBestAsk() {
	    return getAsks().lastEntry();
	  }

	  /**
	   * @return the best bid in the order book
	   */
	  private Map.Entry<BigDecimal, BigDecimal> getBestBid() {
	    return getBids().firstEntry();
	  }

	  /**
	   * @return a depth cache, containing two keys (ASKs and BIDs), and for each, an ordered list of book entries.
	   */
	  public Map<String, NavigableMap<BigDecimal, BigDecimal>> getDepthCache() {
	    return depthCache;
	  }

	  public void close() throws IOException {
	    webSocket.close();
	  }

	  /**
	   * Prints the cached order book / depth of a symbol as well as the best ask and bid price in the book.
	   */
	  private void printDepthCache() {
	    System.out.println(depthCache);
	    System.out.println("ASKS:(" + getAsks().size() + ")");
	    getAsks().entrySet().forEach(entry -> System.out.println(toDepthCacheEntryString(entry)));
	    System.out.println("BIDS:(" + getBids().size() + ")");
	    getBids().entrySet().forEach(entry -> System.out.println(toDepthCacheEntryString(entry)));
	    System.out.println("BEST ASK: " + toDepthCacheEntryString(getBestAsk()));
	    System.out.println("BEST BID: " + toDepthCacheEntryString(getBestBid()));
	  }

	  /**
	   * Pretty prints an order book entry in the format "price / quantity".
	   */
	  private static String toDepthCacheEntryString(Map.Entry<BigDecimal, BigDecimal> depthCacheEntry) {
	    return depthCacheEntry.getKey().toPlainString() + " / " + depthCacheEntry.getValue();
	  }

	 

	  private final class WsCallback implements BinanceApiCallback<DepthEvent> {

	    private final AtomicReference<Consumer<DepthEvent>> handler = new AtomicReference<>();

	    @Override
	    public void onResponse(DepthEvent depthEvent) {
	      try {
	        handler.get().accept(depthEvent);
	      } catch (final Exception e) {
	        System.err.println("Exception caught processing depth event");
	        e.printStackTrace(System.err);
	      }
	    }

	    @Override
	    public void onFailure(Throwable cause) {
	      System.out.println("WS connection failed. Reconnecting. cause:" + cause.getMessage());

	      initialize();
	    }

	    private void setHandler(final Consumer<DepthEvent> handler) {
	      this.handler.set(handler);
	    }
	  }
	
	
	
	
	public static void main(String[] args) {
		
		Jedis jedis=new Jedis("localhost");
	
		System.out.println("Establishing connection to redis.. ");
		
		    
		File configFile = new File("./src/main/java/initialconfig.properties");
		File configFile2 = new File("./src/main/java/symbols.info");
	
		BufferedReader reader;
		
		try {
		
		   reader = new BufferedReader(new FileReader(
				   configFile2));
			String line = reader.readLine();
			while (line != null) {
				System.out.println(line);
				 System.out.println("watching currency :" + line);
				 new binanceapi(line);
				 line=reader.readLine();
				
			}
			reader.close();
		} catch (IOException e) {
			e.printStackTrace();
		} 
		   

		/* /home/lavaraja/Downloads/binance-java-api-master/src/main/java */
		
	
		   
		   
		try {
		
		    FileReader reader1 = new FileReader(configFile);
		    Properties props = new Properties();
		    props.load(reader1);
		 
		    String host = props.getProperty("host");
	
		    reader1.close();
		    
		} catch (FileNotFoundException ex) {
			 System.out.print("configuration file not ofund");
		} catch (IOException ex) {
			 System.out.print("IO error occured. Configuration is not loaded.");
		}
		// TODO Auto-generated method stub
	/*
		BinanceApiClientFactory factory = BinanceApiClientFactory.newInstance("APIKEY", "SECRET");
		BinanceApiRestClient client = factory.newRestClient();
		long serverTime = client.getServerTime();
		System.out.println(serverTime);
		String symbol="BTCUSDT";
		
		 OrderBook orderBook = client.getOrderBook(symbol.toUpperCase(), 10);
		 System.out.println( orderBook.getLastUpdateId());*/
	}

	
}
