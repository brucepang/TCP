import java.nio.*;
import java.util.*;
import java.util.concurrent.*;
import java.lang.reflect.Method;

public class TCPSockSender {
    public static final int DEFAULT_BUFFER_SZ = 65536;

	private TCPSock sock;
	private int nextSeqNum;
	private Segment prevSegment;
    private ByteBuffer senderBuffer = ByteBuffer.allocate(DEFAULT_BUFFER_SZ);

    private int curTimerId;

	public TCPSockSender(TCPSock sock){
		this.sock = sock;
		nextSeqNum = 0;
        curTimerId = 0;
	}


	public void send(int type, byte[] payload){
        //  SYN = 0;
        //  ACK = 1;
        //  FIN = 2;
        //  DATA = 3;
        this.sock.logOutput("Send type "+type+" , Seq num: "+this.nextSeqNum);
        send(type,nextSeqNum,payload);
        incrementSeqNum(payload.length);
	}

	public void send(int type, int seqNum, byte[] payload){
		this.sock.send(type,0,seqNum,payload);
		prevSegment = new Segment(type,seqNum,payload);

		try {
            String[] types = {"java.lang.Integer"};
            Object[] params = { curTimerId };
            Method method = Callback.getMethod("timeout", this, types);
            Callback cb = new Callback(method, this, params);
            this.sock.getNode().getManager().addTimer(this.sock.getNode().getAddress(), 1000, cb);
        }catch(Exception e) {
            this.sock.logError("Failed to add timer callback. Method Name: " +
                 "\nException: " + e);
        }
	}

	public void sendNextSegment(){
        // this.logOutput("buffer size: "+sendermBuffer.position());
        if(hasEmptyBuffer()){
            return;
        }
        int size = Math.min(senderBuffer.position(), Transport.MAX_PAYLOAD_SIZE);
        byte[] payload = new byte[size];
        senderBuffer.flip();
        senderBuffer.get(payload, 0, size);
        senderBuffer.compact();
        send( Transport.DATA, payload );
    }

    public void receivedACKForData(int seqNum){
    	sendNextSegment();
    }

	public void timeout(Integer timerId){
        if(timerId == curTimerId){
            this.sock.logOutput("Timing out: " + prevSegment.getSeqNum() + " Rescend!");
            this.sock.logOutput("previous segment has seq number: "+prevSegment.getSeqNum());
            resend(prevSegment);
        }
        else{
            // this.sock.logOutput("Timing out: " + prevSegment.getSeqNum() + " No Resend!");
        }
    }


    private void resend(Segment prevSegment){
        // setSeqNum(this.nextSeqNum-prevSegment.getPayload().length);
        this.sock.logOutput("Resend type "+prevSegment.getType()+" , Seq num: "+prevSegment.getSeqNum());
        this.sock.logCode("!");
        send(prevSegment.getType(),prevSegment.getSeqNum(),prevSegment.getPayload());
    }


    public void writeToBuffer(byte[] data){
    	this.senderBuffer.put(data);
    }

    public int getNextSeqNum(){
    	return nextSeqNum;
    }
	public void incrementSeqNum(int inc){
		this.nextSeqNum+=inc;
	}

    public void incrementTimer(){
        curTimerId++;
    }

    public void setSeqNum(int seqNum){
        this.nextSeqNum = seqNum;
    }

    public boolean hasEmptyBuffer(){
        return senderBuffer.position()==0;
    }
}