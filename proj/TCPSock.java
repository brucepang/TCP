/**
 * <p>Title: CPSC 433/533 Programming Assignment</p>
 *
 * <p>Description: Fishnet socket implementation</p>
 *
 * <p>Copyright: Copyright (c) 2006</p>
 *
 * <p>Company: Yale University</p>
 *
 * @author Hao Wang
 * @version 1.0
 */
import java.nio.*;
import java.util.*;
import java.util.concurrent.*;
import java.lang.reflect.Method;

public class TCPSock {
    // TCP socket states
    enum State {
        // protocol states
        CLOSED,
        LISTEN,
        SYN_SENT,
        ESTABLISHED,
        SHUTDOWN // close requested, FIN not sent (due to unsent data in queue)
    }

    enum Role {
        UNDETERMINED,
        SENDER,
        RECEIVER,
        LISTENER
    }

    private static final boolean LOG = true;
    private static final int DEFAULT_TIMEOUT = 1000;

    private State state;
    private Role role;
    private Node node;
    private TCPManager tcpMan;

    private TCPSockSender sender;
    private TCPSockReceiver receiver;

    private int myPort;
    private int destAddr;
    private int destPort;
    private boolean bound = false; //if bind to a port
    private ArrayBlockingQueue<TCPSock> socketQueue; 
    private int nextSeqNum;
    private int expectedSeqNum;
    private ByteBuffer receiverBuffer = ByteBuffer.allocate(DEFAULT_BUFFER_SZ);
    private ByteBuffer senderBuffer = ByteBuffer.allocate(DEFAULT_BUFFER_SZ);


    private Segment prevACKSegment;
    private int prevACK;
    private int curCloseTimerId;

    public static final int DEFAULT_BUFFER_SZ = 65536;


    public TCPSock(TCPManager manager, Node node) {
        this.node = node;
        this.tcpMan = manager;
        this.nextSeqNum = 0;
        this.role = Role.UNDETERMINED;
        this.prevACK = -1;
        this.curCloseTimerId = 0;
    }

    private TCPSock(TCPManager manager, Node node, int destAddr, int destPort, int myPort,  
        Transport transport) 
    {
        this(manager, node);
        this.expectedSeqNum = transport.getSeqNum()+1;
        state = State.ESTABLISHED;
        role = Role.RECEIVER;
        this.myPort = myPort;
        this.destAddr = destAddr;
        this.destPort = destPort;
        sendACK();
    }

    /*
     * The following are the socket APIs of TCP transport service.
     * All APIs are NON-BLOCKING.
     */

    /**
     * Bind a socket to a local port
     *
     * @param localPort int local port number to bind the socket to
     * @return int 0 on success, -1 otherwise
     */
    public int bind(int localPort) {
        logOutput("bind");
        if(bound) return -1;
        if(!tcpMan.checkAvailablePort(localPort)) return -1;
        tcpMan.assignPort(this,localPort);
        this.myPort = localPort;
        bound = true;
        return 0;
    }

    /**
     * Listen for connections on a socket
     * @param backlog int Maximum number of pending connections
     * @return int 0 on success, -1 otherwise
     */
    public int listen(int backlog) {
        logOutput("listen");
        if(!bound) return -1;
        this.state = State.LISTEN;
        this.role = Role.LISTENER;

        this.socketQueue = new ArrayBlockingQueue<>(backlog);
        return 0;
    }

    /**
     * Accept a connection on a socket
     *
     * @return TCPSock The first established connection on the request queue
     */
    public TCPSock accept() {
        if (this.role != Role.LISTENER) return null;
        return this.socketQueue.poll();
    }


    public boolean isConnectionPending() {
        return (state == State.SYN_SENT);
    }

    public boolean isClosed() {
        return (state == State.CLOSED);
    }

    public boolean isConnected() {
        return (state == State.ESTABLISHED);
    }

    public boolean isClosurePending() {
        return (state == State.SHUTDOWN);
    }

    /**
     * Initiate connection to a remote socket
     *
     * @param destAddr int Destination node address
     * @param destPort int Destination port
     * @return int 0 on success, -1 otherwise
     */
    public int connect(int destAddr, int destPort) {
        logOutput("connect");
        if(!bound) return -1;
        this.destAddr = destAddr;
        this.destPort = destPort;

        sender = new TCPSockSender(this);
        sender.send(Transport.SYN,new byte[0]);
        sender.incrementSeqNum(1);
        // send(Transport.SYN, 0, this.nextSeqNum, new byte[0]);
        // this.nextSeqNum += 1;
        this.state = State.SYN_SENT;
        this.role = Role.SENDER;
        return 0;

    }

    /**
     * Initiate closure of a connection (graceful shutdown)
     */
    public void close() {
        if(this.role == Role.LISTENER || this.role == Role.UNDETERMINED)
            return;
        if(!sender.hasEmptyBuffer()){
            sender.sendNextSegment();
            return;
        }
        sender.send(Transport.FIN,new byte[0]);
        sender.incrementSeqNum(1);
        // send(Transport.FIN,0,this.nextSeqNum, new byte[0]);
        // this.nextSeqNum++;
        this.state = State.SHUTDOWN;
    }

    /**
     * Release a connection immediately (abortive shutdown)
     */
    public void release() {
        if(isClosed())
            return;
        this.state = State.CLOSED;
        if(this.role == Role.LISTENER){
            this.tcpMan.unbindSocket(this.destAddr,this.destPort,this.myPort);
            return;
        }
        else{
            this.tcpMan.unbindSocket(this.myPort);
        }

    }

    /**
     * Write to the socket up to len bytes from the buffer buf starting at
     * position pos.
     *
     * @param buf byte[] the buffer to write from
     * @param pos int starting position in buffer
     * @param len int number of bytes to write
     * @return int on success, the number of bytes written, which may be smaller
     *             than len; on failure, -1
     */

    public int write(byte[] buf, int pos, int len) {
        if(isClosed() || this.role!=Role.SENDER) return -1;
        // len = Math.min(Transport.MAX_PAYLOAD_SIZE,len);
        int written = 0;
        byte[] payload = new byte[len];
        for (int i = 0; i < Math.min(len,senderBuffer.remaining()); i++ ) {
            payload[i] = buf[pos+i];
            written++;
        }
        // send(Transport.DATA,0,this.nextSeqNum,payload);
        // senderBuffer.put(payload);
        sender.writeToBuffer(payload);
        return written;
    }

    /**
     * Read from the socket up to len bytes into the buffer buf starting at
     * position pos.
     *
     * @param buf byte[] the buffer
     * @param pos int starting position in buffer
     * @param len int number of bytes to read
     * @return int on success, the number of bytes read, which may be smaller
     *             than len; on failure, -1
     */
    public int read(byte[] buf, int pos, int len) {
        if(isClosed() || this.role!=Role.RECEIVER)
            return -1;
        receiverBuffer.flip();
        int read = Math.min(receiverBuffer.remaining(), len);
        receiverBuffer.get(buf, pos, read);
        receiverBuffer.compact();

        return read;
    }

    /*
     * End of socket API
     */
    public void send(int type, int window, int seqNum, byte[] payload) {
        this.tcpMan.send(this.myPort, this.destAddr, this.destPort, 
            type, window, seqNum, payload);
        switch(type){
            case Transport.DATA: 
                logOutput(".");
                logCode(".");
                break;
                // System.out.print(".");
            case Transport.SYN: 
                logOutput("S");
                logCode("S");
                break;
                // System.out.print("S");
            case Transport.FIN: 
                logOutput("F");
                logCode("F");
                break;
        }
    }

    public void sendACK(){
        this.logOutput("Send ACK with seqNum: "+this.expectedSeqNum);
        prevACKSegment = new Segment(Transport.ACK,this.expectedSeqNum,new byte[0]);
        send(Transport.ACK,0,this.expectedSeqNum,new byte[0]);
    }

    public void resendACK(){
        this.logCode("!");
        send(prevACKSegment.getType(),0,prevACKSegment.getSeqNum(),prevACKSegment.getPayload());
    }

    // public void send(int type, int window, int seqNum, byte[] payload){

    //     this.node.logOutput("Send from "+this.myPort+" to "+this.destAddr+"."+this.destPort + " Seq num: "+seqNum);
    //     this.tcpMan.send(this.myPort, this.destAddr, this.destPort, 
    //         type, window, seqNum, payload);

    //     prevSegment = new Segment(type,seqNum,payload);
    //     if(this.role != Role.RECEIVER){
    //         try {
    //             String[] types = {"java.lang.Integer"};
    //             Object[] params = { seqNum };
    //             Method method = Callback.getMethod("timeout", this, types);
    //             Callback cb = new Callback(method, this, params);
    //             this.node.getManager().addTimer(this.node.getAddress(), 1000, cb);
    //         }catch(Exception e) {
    //             this.node.logError("Failed to add timer callback. Method Name: " +
    //                  "\nException: " + e);
    //         }
    //     }   
    //     // if(this.role == Role.SENDER)
    //     this.nextSeqNum += payload.length;

    //     switch(type){
    //         case Transport.DATA: 
    //             logOutput(".");
    //             logCode(".");
    //             break;
    //             // System.out.print(".");
    //         case Transport.SYN: 
    //             logOutput("S");
    //             logCode("S");
    //             break;
    //             // System.out.print("S");
    //         case Transport.FIN: 
    //             logOutput("F");
    //             logCode("F");
    //             break;
    //             // System.out.print("F");
    //     }
    // }


    public void receive(int srcAddr, int srcPort, Transport transport){
        if(isClosed()) return;
        if(transport.getSeqNum() == prevACK){
            sendACK();
            return;
        }
        else{
            prevACK = transport.getSeqNum();
        }
        // }
        switch (transport.getType()) {
            case Transport.SYN:
                receiveSYN(srcAddr, srcPort, transport);
                break;

            case Transport.ACK:
                receiveACK(transport);
                break;

            case Transport.DATA:
                receiveDATA(transport);
                break;

            case Transport.FIN:
                receiveFIN(transport);
                break;
        }
    }


    public void receiveSYN(int srcAddr, int srcPort, Transport transport){
        logOutput("S");
        logCode("S");
        if(this.role == Role.RECEIVER){
            resendACK();
            return;
        }
        else{
            if(this.socketQueue.remainingCapacity() == 0){
                this.tcpMan.logError("Backlog full!");
                return;
            }
            TCPSock socket = new TCPSock(this.tcpMan, this.node, srcAddr, srcPort, this.myPort, transport);

            if(this.tcpMan.checkAvailableSocket(srcAddr,srcPort,this.myPort)){
                this.tcpMan.logError("Address unavailable!");
                return;
            }
            else{
                this.tcpMan.bindSocket(srcAddr,srcPort,this.myPort,socket);
            }
            
            try{
                this.socketQueue.add(socket);
            }
            catch(IllegalStateException e){
                socket.release();
                this.tcpMan.logError("Could not add to socket queue!");
            }
        }

    }

    public void receiveACK(Transport transport){
        if(this.role != Role.SENDER) return;
        logOutput("Receive ACK for seq num: "+transport.getSeqNum());
        sender.incrementTimer();
        receiveACKforData(transport);
        receiveACKforSYN(transport);
        receiveACKforFIN(transport);
    }


    private void receiveACKforSYN(Transport transport){
        if(!isConnectionPending()) return;

        if(transport.getSeqNum()!=this.sender.getNextSeqNum()){
            this.node.logOutput("?");
            logCode("?");
            this.tcpMan.logError("SYN ACK seq number mismatch!"+this.sender.getNextSeqNum()+ " : "+transport.getSeqNum());
        }
        else{
            this.state = State.ESTABLISHED;
            logOutput("Connected!");
            logOutput(":");
            logCode(":");
            sender.sendNextSegment();
        }
    }

    private void receiveACKforFIN(Transport transport){
        if (!isClosurePending()) return;

        if(transport.getSeqNum()!=this.sender.getNextSeqNum()){
            this.node.logOutput("?");
            logCode("?");
            this.tcpMan.logError("FIN ACK seq number mismatch!"+this.sender.getNextSeqNum()+ " : "+transport.getSeqNum());
        }
        else{
            release();
            logOutput("Closed!");
            logCode(":");
            // this.node.logOutput(":");
        }    
    }


    private void receiveACKforData(Transport transport){
        if(this.role!=Role.SENDER || !isConnected()) 
            return;
        sender.receivedACKForData(transport.getSeqNum());
    }

    // private void sendNextSegment(){
    //     // this.logOutput("buffer size: "+senderBuffer.position());
    //     if(senderBuffer.position()==0){
    //         return;
    //     }
    //     int size = Math.min(senderBuffer.position(), Transport.MAX_PAYLOAD_SIZE);
    //     byte[] payload = new byte[size];
    //     senderBuffer.flip();
    //     senderBuffer.get(payload, 0, size);
    //     senderBuffer.compact();
    //     send(Transport.DATA,0,this.nextSeqNum,payload);
    // }


    private void receiveDATA(Transport transport){
        logOutput(".");   
        logCode(".");
        if(this.role != Role.RECEIVER) return;
        receiverBuffer.put(transport.getPayload());
        this.expectedSeqNum += transport.getPayload().length;
        sendACK();
    }

    private void receiveFIN(Transport transport){
        logOutput("F");
        logCode("F");
        if(this.role != Role.RECEIVER) return;
        this.expectedSeqNum = transport.getSeqNum()+1;
        this.curCloseTimerId++;
        sendACK();
        logOutput("Wait for a while before release");
        try {
            String[] types = {"java.lang.Integer"};
            Object[] params = { this.curCloseTimerId };
            Method method = Callback.getMethod("closeTimeout", this, types);
            Callback cb = new Callback(method, this, params);
            this.node.getManager().addTimer(this.node.getAddress(), 2000, cb);
        }catch(Exception e) {
            this.node.logError("Failed to add timer callback. Method Name: " +
                 "\nException: " + e);
        }
    }

/**
LISTENER Socket API
**/

    public void logOutput(String output){
        if(LOG) this.node.logOutput(output);
    }

    public void logError(String output){
        this.node.logError(output);
    }

    public void logCode(String output){
        if(!LOG) System.out.print(output);
    }


    public void closeTimeout(Integer timerID){
        // if previous segment has 0 payload, it means it is a syn, ack, or fin.
        // if(prevSegment.getPayload().length == 0){
        //     seq++;
        // }
        if(timerID == curCloseTimerId){
            logOutput("closing");
            release();
        }
        else{
            // logOutput("Timing out: " + seq + " No Resend!");
        }
    }

    // private void resend(Segment prevSegment){
    //     this.nextSeqNum -= prevSegment.getPayload().length;
    //     send(prevSegment.getType(),0,prevSegment.getSeqNum(),prevSegment.getPayload());
    // }

    public Node getNode(){
        return this.node;
    }


}
