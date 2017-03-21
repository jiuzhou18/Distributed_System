
import java.io.*;
import java.rmi.Naming;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.server.UnicastRemoteObject;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;




public class Peer extends UnicastRemoteObject implements PeerI{
	
	private static String PeerUrl = "rmi://127.0.0.1"; 
	private static int Port = 1109; //Each peer has different port. 
	private static String filepath = "files"; //The relative path where saves those txt files.
	private static String peerid = "09";
	private static int messageNo = 1;
	private static int maxTTL = 8; //Set a same start TTL for every Peer.
	private static int looptime = 1; //The sequence request time. Default is 1. 
	
	//Use ConcurrentHashMap and ConcurrentLinkedQueue synchronously save data from multiple peers, and it's safe for threads.
	
	//The Map messageToPeer record the key of message ID with value of its upstream peer ID.
	private static ConcurrentHashMap<String, String> msgToPeer = new ConcurrentHashMap<>();
	//The Queue maintain the sequence of message. when its size is msgsize and new massage is coming, the oldest one will be removed.
	private static ConcurrentLinkedQueue<String> msglist= new ConcurrentLinkedQueue<String>();
	private static int msgsize = 50;
	
	//The Map msg_responces record the key of the message ID start from this peer with the value of its query start time and all returned query hit times.
	private static ConcurrentHashMap<String, ConcurrentLinkedQueue<Long>> msgResponces = new ConcurrentHashMap<>();
	
	//The filemap record the key of the file name with the value of the newest query message ID.
	private static ConcurrentHashMap<String, String> filemap = new ConcurrentHashMap<>();
			
	private static String[] neighbours = {"rmi://127.0.0.1:1108/Peer","rmi://127.0.0.1:1110/Peer"}; 
	
/*------------------start change---------------------*/
	
	//The Map messageToPeer record the key of message ID with value of its hit peers ID for future obtaining and its states such as modify date, consistency state.
	private static ConcurrentHashMap<String, ConcurrentLinkedQueue<String[]>> msgHit = new ConcurrentHashMap<>();
	//The Queue maintain the sequence of message hit. when its size is hitsize and new hit is coming, the oldest one will be removed.
	private static ConcurrentLinkedQueue<String> hitlist= new ConcurrentLinkedQueue<String>();
	private static int hitsize = 20;

	//The Map originFile record the key of master copy filename with value of the file's info, such as version Number, modify date, consistency state.
	private static HashMap<String, FileInfo> originFile = new HashMap<>();
	//The Map cachedFile record the key of cached copy filename with value of the file's info, such as version Number, modify date, consistency state.
	private static HashMap<String, CachedInfo> cachedFile = new HashMap<>();
	
	//The Map messageToPeer record the key of invalidating message ID with value of its upstream peer ID. It's used for spread invalidating.
	private static ConcurrentHashMap<String, String> invalidmsgToPeer = new ConcurrentHashMap<>();
	//The Queue maintain the sequence of invalidating message. when its size is invalidmsgsize and new massage is coming, the oldest one will be removed.
	private static ConcurrentLinkedQueue<String> invalidmsglist= new ConcurrentLinkedQueue<String>();
	private static int invalidmsgsize = 20;
	
	//The default time-to-refresh. The dimension is second.
	private static int TTR = 1*60;
	private static String cachedfilepath = "cachedfiles"; //The relative path where saves those cached files.
	private static String mode = "None"; //The on/off of push or pull mode. Default is both off.
/*-------------------end change---------------------*/
	
	
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
       
/*------------------start change---------------------*/        			
            try {
            	//If this peer has the file, call queryhit() back to upstream peer.
            	//Change: Depends on whether it's master or cached copy, input different boolean identifier.
            	if(originFile.containsKey(filename)){ 
            		PeerI peer = (PeerI) Naming.lookup(upstream);
            		peer.queryhit(message, maxTTL-TTL+1, filename, PeerUrl, Port, originFile.get(filename), true);
            	}else if(cachedFile.containsKey(filename) && cachedFile.get(filename).state == FileState.VALID){//For cached file, the state should be valid.
            		PeerI peer = (PeerI) Naming.lookup(upstream);
            		peer.queryhit(message, maxTTL-TTL+1, filename, PeerUrl, Port, cachedFile.get(filename).info, false);
            	}
/*------------------end change---------------------*/
        			
            } catch (Exception e) {
            	System.out.println("Peer Query - start Queryhit Error: " + e.getMessage());
            	return;
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
	public void queryhit(String messageID, int TTL, String filename, String peerIP, int portnumber, FileInfo info, boolean origin)throws RemoteException{
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
				
/*------------------start change---------------------*/
				//Change: Add more info when queryhit, such as last-modify-time.
				String[] peerUrl = new String[3];
				peerUrl[0] = peerIP + ":" + portnumber + "/Peer"; //First record sender peer IP.
				SimpleDateFormat df=new SimpleDateFormat("yyyy-MM-dd hh:mm:ss");
				peerUrl[1] = df.format(info.time.getTime()); //Second record file modification time.
				peerUrl[2] = origin? "Origin" : "Cached copy"; //Third record whether it is origin.
				msgHit.get(messageID).add(peerUrl);	
				hitlist.add(messageID);
/*-------------------end change---------------------*/
				
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
    				peer.queryhit(messageID, TTL-1, filename, peerIP, portnumber, info, origin);
    			} catch(Exception e) {
    				System.out.println("Peer Queryhit - transmit Queryhit Error: " + e.getMessage());
    				return;
    			}				
			}
		}
		
	}

/*------------------start change---------------------*/
	//Implement obtain method required by assignment.
	//Read the file which name is filename received from other peer, save it to buffer and send the buffer to the callee.
	//Change: return more info about the file, which is recorded in object filepack.
	public FilePackage obtain(String filename) throws RemoteException{
		File file;
		if(originFile.containsKey(filename)){
			file = new File(filepath + "/" + filename);		
		}else if (cachedFile.containsKey(filename)){
			file = new File(cachedfilepath + "/" + filename);	
		}else return null;
		if(!file.exists()){
			System.out.println("Obtain Error: the file " +  filename +"is incorrectly appear in originFile or cachedFile map.");
			return null;
		}
		byte buffer[] = new byte[(int) file.length()];
		try {
			//Read in the file.
			BufferedInputStream filein = new BufferedInputStream(new FileInputStream(file));
			filein.read(buffer, 0, buffer.length);
			filein.close();
			
			//Encapsulate file data with its states into filepack and return to the peer client.
			if(originFile.containsKey(filename)){
				FileInfo info = originFile.get(filename);
				FilePackage filepack = new FilePackage(info.versionNo, info.time, Calendar.getInstance(), TTR, PeerUrl+":"+Port+"/Peer", FileState.VALID, buffer);
				return filepack;
			}else{
				CachedInfo info = cachedFile.get(filename); 
				FilePackage filepack = new FilePackage(info.info.versionNo, info.info.time, info.cachedTime, info.TTR, info.originIP, info.state, buffer);
				return filepack;
			}
		} catch (Exception e) {
			System.out.println("File Retrieve Error: " + e.getMessage());
			return null;
		}
	}

	//Implement new invalidation function.
	//When invalidating, the messageID contains messageID and also upstreamID separated by "#".
	public void invalidation(String messageID, String originIP, String filename, int versionNo, int TTL){
		String[] IDs = messageID.split("#");
		String message = IDs[0];
		String upstream = IDs[1];
		
		//If this invalidation has not come to this peer, start checking.
		if(!invalidmsgToPeer.containsKey(message)){
			//Maintain the invalidmsglist and msgToPeer.
			while(invalidmsglist.size() >= invalidmsgsize){
				String oldmsg = invalidmsglist.poll();
				invalidmsgToPeer.remove(oldmsg);
			}
			invalidmsglist.add(message);
			invalidmsgToPeer.put(message, upstream);
			
			//If this peer has kept this cached file, check its version Number.
			if(cachedFile.containsKey(filename) && cachedFile.get(filename).state == FileState.VALID){//For cached file, the state should be valid.
        		CachedInfo info = cachedFile.get(filename);
				int thisVersion = info.info.versionNo;
				if(thisVersion < versionNo){
					info.state = FileState.INVALID;
					System.out.println("\nInvalidation Notice: The file "+ filename +" is invalid now.\n");
				}
        	}
              			
        	//keep transmit the invalidation query to other neighbors peers.
        	if(TTL != 1){
        		for(String str: neighbours){
        			PeerI peer;
        			if(str.equals(upstream)) continue; //Skip the upstream peer.
        			try {
        				peer = (PeerI) Naming.lookup(str);
        				peer.invalidation(message+"#"+PeerUrl+":"+Port+"/Peer", originIP, filename, versionNo, TTL-1);
        			} catch(Exception e) {
        				System.out.println("Peer Invalidation - transmit Error: " + e.getMessage());
        				return;
        			}
        		}
        	}
		}
		
	}
	
	//The new pull function simply check whether the versionNo is the latest one.
	//If versionNo is same, return fresh TTR. Otherwise, return 0. Additionally, if server cannot find file in list, return -1;
	public int pull(String filename, int versionNo){
		if(!originFile.containsKey(filename)) return -1;
		FileInfo info = originFile.get(filename);
		if(info.versionNo > versionNo) return 0;
		else return TTR;
	}
	
/*-------------------end change---------------------*/		
	
	
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
			PullMode pull = null;			
			recordFile();
			Update update = new Update();
			update.start();
			while(true){
				System.out.println("\n---------------------------------");
				System.out.println("The menu options for user:");
				System.out.println("1.Set loop times for test (now is: " + looptime +")");
				System.out.println("2.Query files");
				System.out.println("3.Calculate the average responce time of latest query");
				System.out.println("4.Obtain files");
				System.out.println("5.Set a timer to call Search (So as to run with other peer simultaneously)");
/*-------------------start change---------------------*/	
				System.out.println("6.Set TTR (now is :" + TTR +")");
				System.out.println("7.Set push/pull mode. (now: " + mode + ")");
				System.out.println("8.Simply modify file (change last-modify-time to now).");
				System.out.println("9.Show master copy and cached copy on this peer.");
/*-------------------end change---------------------*/	
				System.out.println("10.Fresh menu");
				System.out.println("11.Exit");
				System.out.println("---------------------------------");
				System.out.println("Please select a option number:");
				int option = getSelection(in, 1, 11);
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
					if(originFile.containsKey(filename)) System.out.println("Warning: You are the origin server for this file. Do not need download.");
					else executeDownload(in, filename);
					System.out.println();
					break;
				case 5:
					TimerLookup(in, this);
					System.out.println();
					break;
/*-------------------start change---------------------*/	
				case 6:
					System.out.println("Please enter the TTR time(s):");
					TTR = getSelection(in, 1, Integer.MAX_VALUE);
					System.out.println("The TTR time has been set as: " + TTR +".\n");
					break;
				case 7:
					System.out.println("Please select a option mode: 1.Push 2.Pull 3.None");
					int choice = getSelection(in, 1, 3);
					switch(choice){
					case 1:
						if(pull != null){
							pull.interrupt();
							pull = null;
						}
						mode = "Push";
						break;
					case 2:
						pull = new PullMode();
						pull.start();
						mode = "Pull";
						break;
					case 3:
						if(pull != null){
							pull.interrupt();
							pull = null;
						}
						mode = "None";
					}
					break;
				case 8:
					System.out.println("Please enter the file name of master copy:");
					filename = in.nextLine();
					changeModifyTime(filename);
					break;
				case 9:
					showCopyInfo(in);
					break;
/*-------------------end change---------------------*/	
				case 10:
					break;
				case 11:
					if(mode.equals(pull)) pull.interrupt();
					System.out.println("Peer (" + PeerUrl + ":" + Port + "/Peer) is exitting...\n");
					in.close();
					System.exit(0);
				}
			}
		}
		
/*-------------------start change---------------------*/	
		//Initially record the master copy files information into map of originFile.
		public void recordFile(){
            File path = new File(filepath);
            if(!path.exists()){
            	System.out.println("Error: This path " + filepath +" is lost.");
            	System.exit(-1);
            }
            //Record all the files under the path.
            for(String name: path.list()){
            	File file = new File(filepath+"/"+name);              
            	Calendar cal = Calendar.getInstance();   
            	cal.setTimeInMillis(file.lastModified());//Get last modify time of this file.
            	FileInfo info = new FileInfo(1, cal);
            	originFile.put(name, info);	
            }
		}
		
		//This thread will automatically update the record of master copy files. In push mode, it will call invalidation if needed.
		public class Update extends Thread{		
			public void run() {
				try {
					while(true){
						updateRecord();
						Thread.sleep(2000);//Check the file in every two seconds.
					}
				} catch (Exception e) {
					System.out.println("PushMode Exception: " + e.getMessage());
	    			return;
				} 
			}
		}
		
		//This method will update the info for master copy files (in originFile).
		//In push mode, it will call invalidation if the last-modify-time of master copy was changed.
		public void updateRecord(){
			File path = new File(filepath);
            
            //Record all the files under the path.
            for(String name: path.list()){
            	if(!originFile.containsKey(name)){//Ensure the originFile map is correct.
            		System.out.println("Warning: the master copy in /file does not exists in map of originFile.");
            		continue;
            	}
            	File file = new File(filepath+"/"+name);              
            	Calendar cal = Calendar.getInstance();   
            	cal.setTimeInMillis(file.lastModified());//Get last modify time of this file.
            	
            	FileInfo info = originFile.get(name);
            	//When the file in list is old, we should update the originFile and call invalidation to neighbors.
            	if(info.time.compareTo(cal)<0){
        			//Update map of originFile.
        			info.versionNo++;
        			info.time = cal;
        			
        			if(mode.equals("Push")){
	        			String messageID = peerid + "-" + messageNo; //Create a unique message ID. 
						messageNo++;
						String originIP = PeerUrl + ":" + Port + "/Peer";
						String querymsgID = messageID + "#" + originIP; //Unique querymsgID combined with messageID and upstream Peer, uesd for query().
	        			PeerI peer;
	        			for(String str: neighbours){//Call invalidation to neighbors.
							try {
								peer = (PeerI) Naming.lookup(str);
								peer.invalidation(querymsgID, originIP, name, info.versionNo, maxTTL);
							} catch(Exception e) {
								System.out.println("updateRecord - transmit invalidation Error: " + e.getMessage());
								return;
							}
						}
        			}
        		}
            }	
		}
		
		//This thread will automatically check/update the record of master copy files. Used for pull mode.
		public class PullMode extends Thread{		
			public void run() {
				try {
					int time = 0;
					while(true){
						for(CachedInfo e: cachedFile.values()){ 
							if(e.state == FileState.VALID){//Check whether expired
								Calendar cal = Calendar.getInstance();
								cal.add(Calendar.SECOND, -e.TTR);
								if(e.cachedTime.compareTo(cal) < 0){//If last-modify-time exceed the range of TTR.
									e.state = FileState.TTRexpired;
								}
							}
						}
						time++;
						if(time == 15){//For every 30s, call pull function if the file state is TTR expired.
							for(Map.Entry<String, CachedInfo> entry: cachedFile.entrySet()){
								if(entry.getValue().state == FileState.TTRexpired){
									String filename = entry.getKey();
									CachedInfo info = entry.getValue();
									PeerI peer = (PeerI) Naming.lookup(info.originIP);
									int ans = peer.pull(filename, info.info.versionNo);
									
									if(ans <= 0){//Negative means not valid.
										info.state = FileState.INVALID;
										System.out.println("Pull Notice: the file " + filename + " is now invalid.");
										if(ans == -1){
											System.out.println("Pull Error: the file " + filename + "is not in Peer \"" + info.originIP + "\" ");
										}
									}else{
										info.cachedTime = Calendar.getInstance();
										info.TTR = ans;
										info.state = FileState.VALID;//If still valid, Update the TTR.
									}
								}
							}
							time = 0;
						}
						Thread.sleep(2000);//Check the file in every two seconds.
					}
				} catch (Exception e) {
					System.out.println("PullMode Exception: " + e.getMessage());
					return;
				} 
			}
		}
		
		public void changeModifyTime(String filename){
			if(!originFile.containsKey(filename))
				System.out.println("Warning: the origin File list does not contain this file.");
			File fileToChange = new File(filepath + '/' + filename);
			if(!fileToChange.exists())
				System.out.println("Error: the file does not exists but origin File list contains it!");
			if (fileToChange.setLastModified(System.currentTimeMillis())){
				SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd hh:mm:ss");
				System.out.println("Change last modify time to " + df.format(fileToChange.lastModified()) +" Successfully!");
			}
			else
				System.out.println("Error: Changing last modify time Failed!");
		}
		
		public void showCopyInfo(Scanner in){
			System.out.println("----The information for master copy:");
			if(originFile.isEmpty()) System.out.println("No master copy");
			for(Map.Entry<String, FileInfo> e: originFile.entrySet()){
				String filename = e.getKey();
				FileInfo info = e.getValue();
				SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd hh:mm:ss");
				System.out.println(filename + "\t\tVersionNo: " + info.versionNo + "\tLast-Modify-Time: " + df.format(info.time.getTime()));
			}
			System.out.println("----The information for cached copy:");
			if(cachedFile.isEmpty()) System.out.println("No cached copy");
			for(Map.Entry<String, CachedInfo> e: cachedFile.entrySet()){
				String filename = e.getKey();
				CachedInfo info = e.getValue();
				SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd hh:mm:ss");
				System.out.println(filename + "\t\tVersionNo: " + info.info.versionNo + "\tLast-Modify-Time: " + df.format(info.info.time.getTime()) +" (" + info.state + ")" );
				if(mode.equals("Pull"))
					System.out.println("\t\tTTR: " + info.TTR + "\tRefresh Time: " + df.format(info.cachedTime.getTime()) );
			}
			System.out.println("Please press enter to return to the menu.");
			in.nextLine();
		}
		
/*-------------------end change---------------------*/	
		
		
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
					ConcurrentLinkedQueue<String[]> queue = new ConcurrentLinkedQueue<String[]>();
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
			
/*-------------------start change---------------------*/	
			//Change: now the queue recored String[] object.
			ConcurrentLinkedQueue<String[]> queue = msgHit.get(msgid);
			if(queue == null){
				System.out.println("No queried peer has this file "  + filename + "." );
				return;
			}
			
			String[][] peerids = new String[queue.size()][3];
			int index = 0;
			for(String[] e: queue){//Change: Extract all infos from queue.
				peerids[index][0] = e[0];
				peerids[index][1] = e[1];
				peerids[index][2] = e[2];
				index++;
			}

			if(queue.size() == 0) {
				System.out.println("No queried peer has this file "  + filename + "." );
				return;
			}
			
			//Show all the peers who keep the file.
			//Change: also show its last-modify-time and valid state.
			System.out.println("There are all address of the peers keeping file " + filename + ":");
			for(int i=0; i<peerids.length; i++){
				System.out.println((i+1) + ". " + peerids[i][0] +" Time: " + peerids[i][1] + " (" + peerids[i][2] +")");
			}
			System.out.println("\nYou can choose one to download the file: (Or you can enter 0 to cancel)");
			int selection = getSelection(in, 0, peerids.length);
			
			//0 means exit. Otherwise start download.
			if(selection != 0){
				//Call retrieve and calculate the average download time.
				startTime = System.nanoTime();
				byte[] filedata = null;
				try{
					PeerI peerserver = (PeerI) Naming.lookup(peerids[selection-1][0]);
					for(int i=1; i<=looptime; i++){
						//Change: now we get FilePackage object instead of only file data.
						FilePackage filepack = peerserver.obtain(filename);
						filedata = null;
						filedata = filepack.contents;
						if(filedata == null){
							System.out.println("ExecuteDownload Error: This peer keep doesn't this file.");
							return;
						}
						
						//Save the file which has just been download.
						//Change: there is a specific file path for cached files.
						File path = new File(cachedfilepath);
						if(!path.exists()){
							try {
								path.mkdir();
							} catch (Exception e) {
								System.out.println("ExecuteDownload Error: Can not create folder " + cachedfilepath +". Please check.");
								return;
							}
						}

						File downloadFile = new File(cachedfilepath + "/"  + filename);
						if(!downloadFile.exists())
							try {
								downloadFile.createNewFile();
							} catch (Exception e) {
								System.out.println("ExecuteDownload Error: Can not create file " + filename +". Please check.");
								return;
							}
						
						try {
							BufferedOutputStream writer = new BufferedOutputStream(
									new FileOutputStream(downloadFile.getAbsolutePath()));
							writer.write(filedata, 0, filedata.length);
							//Change: Put the file info into cacheFile map.
							CachedInfo info = new CachedInfo(filepack.info.versionNo, filepack.info.time, filepack.cachedTime, filepack.TTR, filepack.originIP, filepack.state);
							cachedFile.put(filename, info);
							
/*--------------------end change---------------------*/	
							writer.close();	
						} catch (IOException e) {
							System.out.println("ExecuteDownload Error: The file " + filename +" can't be writen in.");	            					
							return;
						}
					}
					System.out.println("The file " + filename +" has been saved in the path: " + filepath + "/ Successfully.");
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
