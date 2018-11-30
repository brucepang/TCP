import java.util.*;

public class Segment {
	private int type;
    private int seqNum;
    private byte[] payload;
    public Segment(int type, int seqNum, byte[] payload) {
    	this.type = type;
    	this.seqNum = seqNum;
    	this.payload = payload;
    }

    public byte[] getPayload(){
    	return payload;
    }

    public int getType(){
    	return type;
    }

    public int getSeqNum(){
    	return seqNum;
    }
}
