
import java.rmi.Remote;
import java.rmi.RemoteException;
import java.util.Calendar;
import java.util.Date;
import java.io.Serializable;

public interface PeerI extends Remote{
/*------------------start change---------------------*/	
	public enum FileState {  
		VALID, INVALID, TTRexpired  
	}
	
	//A class used for record the state of master copied file.
	public class FileInfo implements Serializable{
		private static final long serialVersionUID = 2563450484336495059L;
		int versionNo;
		public Calendar time;
		
		public FileInfo(int versionNo, Calendar time){
			this.versionNo = versionNo;
			this.time = time; // The last-modify-time for file.
		}
		
	}
	
	//A class used for record the state of cached copied file. Need record more info.
	public class CachedInfo implements Serializable{
		private static final long serialVersionUID = 6769040514341619913L;
		public FileInfo info;
		public int TTR;
		public String originIP;
		public FileState state;
		public Calendar cachedTime; //Record this time when it is cached or ensuered by pulling.
		
		public CachedInfo(int versionNo, Calendar time, Calendar cachedTime,int TTR, String originIP, FileState state){
			info = new FileInfo(versionNo, time);
			this.TTR = TTR;
			this.originIP = originIP;
			this.state = state;
			this.cachedTime = cachedTime;
		}
	}
	
	//A class used for transmitting file with its state situation.
	public class FilePackage extends CachedInfo implements Serializable{	
		private static final long serialVersionUID = 9121438525181647004L;
		public byte[] contents; //Record the file contents.
		
		public FilePackage(int versionNo, Calendar time, Calendar cachedTime, int TTR, String originIP, FileState state, byte[] contents){
			super(versionNo, time, cachedTime, TTR, originIP, state);
			this.contents = contents;
		}
	}
/*-------------------end change---------------------*/

	//Remote Interface for Peer.
	void query(String messageID, int TTL, String filename) throws RemoteException;
	void queryhit(String messageID, int TTL, String filename, String peerIP, int portnumber, FileInfo info, boolean origin) throws RemoteException;
	FilePackage obtain(String filename) throws RemoteException;
	void invalidation(String messageID, String originIP, String filename, int versionNo, int TTL) throws RemoteException;
	int pull(String filename, int versionNo) throws RemoteException;
}
