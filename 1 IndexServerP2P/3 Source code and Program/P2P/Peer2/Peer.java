
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

public class Peer extends UnicastRemoteObject implements PeerI{
	
	private static String ServerUrl = "rmi://127.0.0.1/IndexServer";
	private static String PeerUrl = "rmi://127.0.1.2";
	private static int Port = 1002; //Each peer has different port.
	private static String filepath = "files"; //The relative path where saves those txt files.
	
	
	//Explicit constructor for this Class. (RMI required)
	public Peer() throws RemoteException {
		super();
	}
	
	//Implement retrieve method required by assignment.
	//Read the file which name is filename received from other peer, save it to buffer and send the buffer to the callee.
	public byte[] retrieve(String filename) throws RemoteException{
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
	
	//Main method is used to run the peer on the PeerUrl on port 1099.
	//The RMI Server will automatically create threads for calls from each peer. (N
	public static void main(String[] args) {
		try {
			System.out.println("start Peer ...");
			Peer peerServer = new Peer();
			System.out.println("RMI registry at port "+ Port +".");
			LocateRegistry.createRegistry(Port); 
			Naming.rebind(PeerUrl+":"+Port+"/Peer", peerServer); 
			System.out.println("Bind Succussfully.");
			
			//Thread for Peer Client to lookup/retrieve files and automatically register files when modify/delete/add.
			new peerClient(ServerUrl).start();
			
		} catch (Exception e) {
			System.out.println("IndexServer Error: " + e);
		}

	}
	
	
		//Thread for Peer Client. 
		//Client user can choose the options of lookup/retrieve files or Exit the Peer.
		public static class peerClient extends Thread{
			
			private String serverurl = "//192.168.0.1/Index";
			private int looptime = 1; //The sequence request time. Default is 1. 
			
			public peerClient(String url){
				this.serverurl = url;
			}
			
			public void run() {
				
				//Thread for automatically registering when files are modified/Deleted/Added.
				registerMonitor autoregister = new registerMonitor(ServerUrl); 
				autoregister.start();
				
				Scanner in = new Scanner(System.in);
				try {
	            	IndexI server = (IndexI) Naming.lookup(serverurl);
	            	while(true){
	            		System.out.println("---------------------------------");
	            		System.out.println("The menu options for user:");
	            		System.out.println("1.Set loop times (now is: " + looptime +")");
	            		System.out.println("2.Lookup (and download) files");
	            		System.out.println("3.Set a timer to call Lookup (So as to run with other peer simultaneously)");
	            		System.out.println("4.Exit");
	            		System.out.println("---------------------------------");
	            		System.out.println("Please select a option number:");
	            		int option = getSelection(in, 1, 4);
	            		switch(option){
	            		case 1:
	            			System.out.println("Please enter the loop time number:");
	            			looptime = getSelection(in, 1, Integer.MAX_VALUE);
	            			System.out.println("The loop time has been set as: " + looptime +".\n");
	            			break;
	            		case 2:
	            			System.out.println("Please enter the file name:");
	            			String filename = in.nextLine();
	            			ArrayList<String> iplist = executeLookUp(looptime, server, filename);
	            			executeDownload(iplist, in, looptime, filename);
	            			System.out.println();
	            			break;
	            		case 3:
	            			TimerLookup(in, looptime, server);
	            			System.out.println();
	            			break;
	            		case 4:
	            			autoregister.interrupt();
	            			server.registry(PeerUrl + ":" + Port + "/Peer", null);
	            			System.out.println("Peer (" + PeerUrl + ":" + Port + "/Peer) is exitting...\n");
	            			in.close();
	            			System.exit(0);
	            		}
	            	}
	            }catch(Exception e) {
	    			System.out.println("PeerClient Error: " + e.getMessage());
	    			return;
	    		}
			}
			
			//The method for Calling Lookup to search which peers keep the file from IndexSever.
			//Save the result which is the list of IP into the "iplist". 
			//Return the list of IP.
			//Execute "looptime" times. It will calculate the average time for execution.
			private static ArrayList<String> executeLookUp(int looptime, IndexI server, String filename){
				long startTime, endTime;
				
    			ArrayList<String> iplist = new ArrayList<String>();
    			startTime = System.nanoTime();
    			try{
    				for(int i=1; i<=looptime; i++){//execute "looptime" times.
    					iplist = server.lookup(filename);
    				}
    			}
    			catch(Exception e) {
	    			System.out.println("PeerClient executeLookUp Error: " + e.getMessage());
	    			return null;
	    		}
    			endTime = System.nanoTime();
    			System.out.printf("The avg time for %d sequence lookup is: %.3f ms\n\n", looptime, (double)(endTime-startTime)/1000000.0/looptime);
    			return iplist;
			}
    		
			//The method for Calling retrieve to download the file from one peer in "iplist".
			//Execute "looptime" times. It will calculate the average time for execution.
			private static void executeDownload(ArrayList<String> iplist, Scanner in, int looptime, String filename){
				long startTime, endTime;
				
				if(iplist.size()==0){
    					System.out.println("Warning: No peer keep this file. Please try another.");
    					return;
    			}

    			//Show all the peers who keep the file.
    			System.out.println("There are all address of the peers keeping file " + filename + ":");
    			for(int i=0; i<iplist.size(); i++){
    				System.out.print((i+1) + ". " + iplist.get(i)+"\n");
    			}
    			System.out.println("\nYou can choose one to download the file: (Or you can enter 0 to cancel)");
    			int selection = getSelection(in, 0, iplist.size());
    			
    			//0 means exit. Otherwise start download.
    			if(selection != 0){
    				//Call retrieve and calculate the average download time.
    				startTime = System.nanoTime();
    				byte[] filedata = null;
    				try{
    					PeerI peerserver = (PeerI) Naming.lookup(iplist.get(selection-1));
    					for(int i=1; i<=looptime; i++){
    						filedata = null;
    						filedata = peerserver.retrieve(filename);
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
			private static void TimerLookup(Scanner in, int looptime, IndexI server){
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
				
				//Set the timer to automatically run LoopUp method at "tasktime".
    			Timer timer = new Timer(); 
			    timer.schedule(new TimerTask() {  
			    	public void run() {  
			    		System.out.println("\nTimer for execting lookup " + filename +" start!");
			    		executeLookUp(looptime, server, filename);
			    		System.out.println();
			    	}  
			    }, tasktime);
			    System.out.println("You create a lookup task for " + filename);
			    System.out.println("It will start at " + tasktime.toString());
			}
		}	
		
		//Thread to Monitor the files in this peer. 
		//If anything changed, it will automatically call registry(..) to IndexServer.
		public static class registerMonitor extends Thread{
			
			private static int SleepTime = 2000; //The interval(ms) between each monitor actions.
			private String serverurl = "//192.168.0.1/Index";
			private IndexI server;
			
			private String suffixList="txt";  //The types of files that peer registered.
			
			public registerMonitor(String url){
				this.serverurl = url;
			}
			
			public void run() {
	            try {
	            	server = (IndexI) Naming.lookup(serverurl);
	            }catch(Exception e) {
	    			System.out.println("Register Monitor Error: " + e.getMessage());
	    			return;
	    		}
	            ArrayList<String> files = new ArrayList<>(); //Record the files names in this peer.
	    		ArrayList<String> newfiles; 
	            File path = new File(filepath);
	            while(true){
	            	newfiles = new ArrayList<>();
	            	Boolean flag = true; //Record whether the files are changed after last update.
	            	int count = 0; //Record the file number after last update.
	            	
	            	//For all the files under the path, check whether a file is modified/deleted.
	            	for(String name: path.list()){
	            		String suffix=name.substring(name.lastIndexOf(".")+1, name.length());
	            		if(suffixList.contains(suffix.trim().toLowerCase())){
	            			newfiles.add(name);
	            			count++;
	            			if(!files.contains(name)) flag = false;
	            		}
	            	}
	            	
	            	//If the files are modified/Deleted/Added, it will start registry to the server.
	            	try {
	            		if(flag == false || count!=files.size()){
	            			files = newfiles;
	            			if(files!=null)	server.registry(PeerUrl + ":" + Port + "/Peer", files);
	            			System.out.println("Notice: Peer ("+ PeerUrl + ":" + Port + "/Peer) automatically update registry.\n");
	            		}
	            		Thread.sleep(SleepTime);
					} catch (Exception e) {
						System.out.println("Register Monitor: " + e.getMessage());
		    			return;
					}
	            }
			}
		}

}
