
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.rmi.Naming;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.server.UnicastRemoteObject;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

public class Peer extends UnicastRemoteObject implements PeerI{
	
	private static String PeerUrl = "rmi://127.0.0.1"; 
	private static int Port = 1106; //Each peer has different port. 
	private static String filepath = "files"; //The relative path where saves those txt files.
	private static String peerid = "06";
	private static int messageNo = 1;
	private static int maxTTL = 2; //Set a same start TTL for every Peer.
	private static int looptime = 1; //The sequence request time. Default is 1. 
	
	//Use ConcurrentHashMap and ConcurrentLinkedQueue synchronously save data from multiple peers, and it's safe for threads.
	
	//The Map messageToPeer record the key of message ID with value of its upstream peer ID.
	private static ConcurrentHashMap<String, String> msgToPeer = new ConcurrentHashMap<>();
	//The Queue maintain the sequence of message. when its size is msgsize and new massage is coming, the oldest one will be removed.
	private static ConcurrentLinkedQueue<String> msglist= new ConcurrentLinkedQueue<String>();
	private static int msgsize = 50;
	
	//The Map messageToPeer record the key of message ID with value of its hit peers ID for future obtaining.
	private static ConcurrentHashMap<String, ConcurrentLinkedQueue<String>> msgHit = new ConcurrentHashMap<>();
	//The Queue maintain the sequence of message hit. when its size is hitsize and new hit is coming, the oldest one will be removed.
	private static ConcurrentLinkedQueue<String> hitlist= new ConcurrentLinkedQueue<String>();
	private static int hitsize = 20;
	
	//The Map msg_responces record the key of the message ID start from this peer with the value of its query start time and all returned query hit times.
	private static ConcurrentHashMap<String, ConcurrentLinkedQueue<Long>> msgResponces = new ConcurrentHashMap<>();
	
	//The filemap record the key of the file name with the value of the newest query message ID.
	private static ConcurrentHashMap<String, String> filemap = new ConcurrentHashMap<>();
			
	private static String[] neighbours = {"rmi://127.0.0.1:1101/Peer"}; 
	
	//Explicit constructor for this Class. (RMI required)
	public Peer() throws RemoteException {
		super();
	}
	
	//Implement query method.
	//When querying, the messageID contains messageID and also upstreamID separated by "#".
	public void query(String messageID, int TTL, String filename) throws RemoteException{
		String[] IDs = messageID.split("#");
		String message = IDs[0];
		String upstream = IDs[1];
		if(!msgToPeer.containsKey(message)){
			//Maintain the msglist and msgToPeer.
			while(msglist.size() >= msgsize){
				String oldmsg = msglist.poll();
				msgToPeer.remove(oldmsg);
			}
			msglist.add(message);
			msgToPeer.put(message, upstream);
			
			//Search the folder to check whether it contains the required file.
            File path = new File(filepath);
        	for(String name: path.list()){ //Check each file under the path.
        		if(name.equals(filename)){ //If this peer has the file, call queryhit() back to upstream peer.
        			try {
        				PeerI peer = (PeerI) Naming.lookup(upstream);
    					peer.queryhit(message, maxTTL-TTL+1, filename, PeerUrl, Port);
    				} catch (Exception e) {
    					System.out.println("Peer Query - start Queryhit Error: " + e.getMessage());
    	    			return;
    				}
        		}
        	}
        	
        	//keep transmit the query to other neighbours peers.
        	if(TTL != 1){
        		for(String str: neighbours){
        			PeerI peer;
        			if(str.equals(upstream)) continue; //Skip the upstream peer.
        			try {
        				peer = (PeerI) Naming.lookup(str);
        				peer.query(message+"#"+PeerUrl+":"+Port+"/Peer", TTL-1, filename);
        			} catch(Exception e) {
        				System.out.println("Peer Query - transmit Query Error: " + e.getMessage());
        				return;
        			}
        		}
        	}
		}
	}
	
	//Implement query method.
	//When querying, the messageID just contains messageID. (Since it do not need upstreamID)
	public void queryhit(String messageID, int TTL, String filename, String peerIP, int portnumber)throws RemoteException{
		long endtime = System.nanoTime();
		// If this peer start query, maintain its msgResponces with response time and msgHit map with peers who keep the file.
		if(msgResponces.containsKey(messageID)){
			ConcurrentLinkedQueue<Long> queue = msgResponces.get(messageID);
			if(queue.size() == 1) System.out.println("messge " + messageID + " hit!");
			queue.add(endtime);
			while(hitlist.size() >= hitsize){
				String oldmsg = hitlist.remove();
				msgHit.remove(oldmsg);
			}
			if(msgHit.containsKey(messageID)){
				String peerUrl = peerIP + ":" + portnumber + "/Peer";
				msgHit.get(messageID).add(peerUrl);	
				hitlist.add(messageID);
			}
		}
		// Else if TTL is great than 1, it will backtrack the queryhit function.
		else{
			if(TTL != 1){
				if(!msgToPeer.containsKey(messageID)){
					System.out.println("Peer Queryhit Warning: The record of upstream peer is missed");
					return;
				}
				String upstream = msgToPeer.get(messageID);
				try {
    				PeerI peer = (PeerI) Naming.lookup(upstream);
    				peer.queryhit(messageID, TTL-1, filename, peerIP, portnumber);
    			} catch(Exception e) {
    				System.out.println("Peer Queryhit - transmit Queryhit Error: " + e.getMessage());
    				return;
    			}				
			}
		}
		
	}
	
	//Implement obtain method required by assignment.
	//Read the file which name is filename received from other peer, save it to buffer and send the buffer to the callee.
	public byte[] obtain(String filename) throws RemoteException{
		File file = new File(filepath + "/" + filename);
		if(!file.exists()) return null;
		byte buffer[] = new byte[(int) file.length()];
		try {
			BufferedInputStream filein = new BufferedInputStream(new FileInputStream(file));
			filein.read(buffer, 0, buffer.length);
			filein.close();
			return buffer;
		} catch (Exception e) {
			System.out.println("File Retrieve Error: " + e.getMessage());
			return null;
		}
	}
	
	
	
	
	//Main method is used to run the peer on the "PeerUrl" with "Port".
	//The RMI Server will automatically create threads for calls from each peer. (N
	public static void main(String[] args) {
		try {
			System.out.println("start Peer ...");
			Peer peerServer = new Peer();
			System.out.println("RMI registry at port "+ Port +".");
			LocateRegistry.createRegistry(Port); 
			Naming.rebind(PeerUrl+":"+Port+"/Peer", peerServer); 
			System.out.println("Bind Succussfully.");
			
			//Thread for Peer Client to provide the functions for users.
			new peerClient().start();
		} catch (Exception e) {
			System.out.println("Peer Main Error: " + e);
		}
	}
	
	//Thread for Peer Client. 
	//Client user can choose the options of search/obtain function or Exit the Peer.
	public static class peerClient extends Thread{
		
		public peerClient(){
		}
		
		public void run() {
			Scanner in = new Scanner(System.in);
			String filename;
			
			while(true){
				System.out.println("\n---------------------------------");
				System.out.println("The menu options for user:");
				System.out.println("1.Set loop times for test (now is: " + looptime +")");
				System.out.println("2.Query files");
				System.out.println("3.Calculate the average responce time of latest query");
				System.out.println("4.Obtain files");
				System.out.println("5.Set a timer to call Search (So as to run with other peer simultaneously)");
				System.out.println("6.Fresh menu");
				System.out.println("7.Exit");
				System.out.println("---------------------------------");
				System.out.println("Please select a option number:");
				int option = getSelection(in, 1, 7);
				switch(option){
				case 1:
					System.out.println("Please enter the loop time number:");
					looptime = getSelection(in, 1, Integer.MAX_VALUE);
					System.out.println("The loop time has been set as: " + looptime +".\n");
					break;
				case 2:
					System.out.println("Please enter the file name:");
					filename = in.nextLine();
					new executeQuery(filename).start();
					break;
				case 3:
					calResponce();
					break;
				case 4:
					System.out.println("Please enter the file name:");
					filename = in.nextLine();
					executeDownload(in, filename);
					System.out.println();
					break;
				case 5:
					TimerLookup(in, this);
					System.out.println();
					break;
				case 6:
					break;
				case 7:
					System.out.println("Peer (" + PeerUrl + ":" + Port + "/Peer) is exitting...\n");
					in.close();
					System.exit(0);
				}
			}
		}
		
		
		//The thread executes the query function with initializing the maps in this peer.
		//Execute "looptime" times. It will calculate the average time for execution.
		protected class executeQuery extends Thread{
			
			private String filename;
			
			public executeQuery( String filename){
				this.filename = filename;
			}
			
			public void run() {
				//Every time when executing Query, clear the msgResponces map.
				msgResponces = new ConcurrentHashMap<>();
				
				for(int i=0; i<looptime; i++){
					String messageID = peerid + "-" + messageNo; //Create a unique message ID. 
					messageNo++;
					String querymsgID = messageID + "#" + PeerUrl + ":" + Port + "/Peer"; //Unique querymsgID combined with messageID and upstream Peer, uesd for query().
					filemap.put(filename, messageID);// Assign the newest messageID to file.
					
					//Initialize the structures.
					while(hitlist.size() >= hitsize){
						String oldmsg = hitlist.poll();
						msgHit.remove(oldmsg);
					}
					ConcurrentLinkedQueue<String> queue = new ConcurrentLinkedQueue<String>();
					msgHit.put(messageID, queue);
					hitlist.add(messageID);
					ConcurrentLinkedQueue<Long> times = new ConcurrentLinkedQueue<Long>();
					msgResponces.put(messageID, times);
					msgResponces.get(messageID).add(System.nanoTime()); //Add start time to the first position in the LinkedQueue.
					
					PeerI peer;
					for(String str: neighbours){
						try {
							peer = (PeerI) Naming.lookup(str);
							peer.query(querymsgID, maxTTL, filename);
						} catch(Exception e) {
							System.out.println("Peer Query - transmit Query Error: " + e.getMessage());
							return;
						}
					}
				}
			}
		}
		
		//The method for calculating the average response time of the latest query.
		private static void calResponce(){
			long total = 0;
			int times = 0;
			for(ConcurrentLinkedQueue<Long> value: msgResponces.values()){
				if(value == null || value.isEmpty()){
					System.out.println("warnning: No lastest query data or you have already calculate it before!");
					return;
				} 
				long start = value.poll();
				while(!value.isEmpty()){
					total += value.poll()-start;
					times++;
				}
			}
			if(total == 0) System.out.println("No responces for lastest query!");
			else System.out.printf("The avg responce time for lastest qurey is: %.3f ms\n", (double)total/1000000.0/times);	
		}
	
		//The method for Calling retrieve to download the file from one peer in "iplist".
		//Execute "looptime" times. It will calculate the average time for execution.
		private static void executeDownload(Scanner in, String filename){
			long startTime, endTime;
			
			if(!filemap.containsKey(filename)){
				System.out.println("Warning: Have not query this file. Please query firstly.");
				return;
			}
			String msgid = filemap.get(filename);
			if(!msgHit.containsKey(msgid)){
				System.out.println("Warning: This message may has expired from hit list. Please query again.");
				return;
			}
			ConcurrentLinkedQueue<String> queue = msgHit.get(msgid);
			if(queue == null){
				System.out.println("No queried peer has this file "  + filename + "." );
				return;
			}
			String[] peerids = (String[])queue.toArray(new String[0]);
			
			if(peerids.length == 0) {
				System.out.println("No queried peer has this file "  + filename + "." );
				return;
			}
			//Show all the peers who keep the file.
			System.out.println("There are all address of the peers keeping file " + filename + ":");
			for(int i=0; i<peerids.length; i++){
				System.out.print((i+1) + ". " + peerids[i]+"\n");
			}
			System.out.println("\nYou can choose one to download the file: (Or you can enter 0 to cancel)");
			int selection = getSelection(in, 0, peerids.length);
			
			//0 means exit. Otherwise start download.
			if(selection != 0){
				//Call retrieve and calculate the average download time.
				startTime = System.nanoTime();
				byte[] filedata = null;
				try{
					PeerI peerserver = (PeerI) Naming.lookup(peerids[selection-1]);
					for(int i=1; i<=looptime; i++){
						filedata = null;
						filedata = peerserver.obtain(filename);
						if(filedata == null){
							System.out.println("ExecuteDownload Error: This peer keep doesn't this file.");
							return;
						}
						
						//Save the file which has just been download.
						File downloadFile = new File(filepath + "/"  + filename);
						if(!downloadFile.exists())
							try {
								downloadFile.createNewFile();
							} catch (IOException e) {
								System.out.println("ExecuteDownload Error: Can not create file " + filename +". Please check.");
								return;
							}
						try {
							BufferedOutputStream writer = new BufferedOutputStream(
									new FileOutputStream(downloadFile.getAbsolutePath()));
							writer.write(filedata, 0, filedata.length);;
							if(i==looptime) System.out.println("The file " + filename +" has been saved in the path: " + filepath + "/ Successfully.");
							writer.close();	
						} catch (IOException e) {
							System.out.println("ExecuteDownload Error: The file " + filename +" can't be writen in.");	            					
							return;
						}
					}
				}
				catch(Exception e) {
					System.out.println("PeerClient executeDownload Error: " + e.getMessage());
					return;
				}
				endTime = System.nanoTime();
				if(filedata!=null)
					System.out.printf("The download speed is %.3f MB/s\n", (double)filedata.length/(endTime - startTime)*1000.0*looptime);
				
			}
		}
    		
			
		//Get the right integer between lower bound and upper bound from user's input. 
		public static int getSelection(Scanner in, int lowerbound, int upperbound){
			int back = lowerbound - 1;
			while (back < lowerbound){
				if (!in.hasNextInt()){
					System.out.println("Error input: Please enter a integer.");
					in.nextLine();
				}
				else {
					back = in.nextInt();
					in.nextLine();
					if (back > upperbound || back < lowerbound){
						System.out.println("Error input: the number exceed the range of options numbers. Please input again.");
						back = lowerbound - 1;
					}
				}
			}
			return back;
		}

		//This method use timer to start LookUp task at the appointed time.
		//Can be used to test multiple peers making LookUp requests simultaneously.
		private static void TimerLookup(Scanner in, peerClient client){
			//Set the task time (hour and minute);
			Calendar calendar = Calendar.getInstance();  
			System.out.println("Please input the timer in format HH MM SS (ex. 11 30 00 means 11:30:00)");
			int hour = in.nextInt();
			int minute = in.nextInt();
			int second = in.nextInt();
			in.nextLine();
			calendar.set(Calendar.HOUR_OF_DAY, hour);
			calendar.set(Calendar.MINUTE, minute);
			calendar.set(Calendar.SECOND, second);
			calendar.set(Calendar.MILLISECOND, 0);
			
			//Get the start time for task.
			Date tasktime = calendar.getTime(); 
			
			System.out.println("Please enter the searching file name:");
			String filename = in.nextLine();
			
			//Set the timer to automatically run Query method at "tasktime".
			Timer timer = new Timer(); 
			timer.schedule(new TimerTask() {  
				public void run() {  
					System.out.println("\nTimer for execting query " + filename +" start!");
					client.new executeQuery(filename).start();
					System.out.println();
				}  
			}, tasktime);
			System.out.println("You create a query task for " + filename);
			System.out.println("It will start at " + tasktime.toString());
		}
	}	
	
}
