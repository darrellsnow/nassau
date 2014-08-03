package org.jvirtanen.nassau.moldudp64;

import static org.jvirtanen.nassau.moldudp64.MoldUDP64.*;
import static org.jvirtanen.nio.ByteBuffers.*;

import java.io.Closeable;
import java.io.IOException;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.DatagramChannel;
import org.jvirtanen.nassau.MessageListener;

/**
 * An implementation of a MoldUDP64 client.
 */
public class MoldUDP64Client implements Closeable {

    private static final int RX_BUFFER_LENGTH = 65535;

    private DatagramChannel channel;

    private SocketAddress requestAddress;

    private MessageListener listener;

    private MoldUDP64ClientStatusListener statusListener;

    private ByteBuffer rxBuffer;
    private ByteBuffer txBuffer;

    private byte[] session;

    protected long expectedSequenceNumber;

    /**
     * Create a client. The underlying datagram channel must not be connected,
     * but it can be either blocking or non-blocking.
     *
     * @param channel the underlying datagram channel
     * @param requestAddress the request server address
     * @param listener the inbound message listener
     * @param statusListener the inbound status event listener
     */
    public MoldUDP64Client(DatagramChannel channel, SocketAddress requestAddress,
            MessageListener listener, MoldUDP64ClientStatusListener statusListener) {
        this.channel = channel;

        this.requestAddress = requestAddress;

        this.listener = listener;

        this.statusListener = statusListener;

        this.rxBuffer = ByteBuffer.allocate(RX_BUFFER_LENGTH);
        this.txBuffer = ByteBuffer.allocate(HEADER_LENGTH);

        this.session  = new byte[SESSION_LENGTH];

        this.expectedSequenceNumber = 1;
    }

    /**
     * Get the underlying datagram channel.
     *
     * @return the underlying datagram channel
     */
    public DatagramChannel getChannel() {
        return channel;
    }

    /**
     * Receive a downstream packet.
     *
     * <p>If the sequence number of the first message in the packet is higher
     * than expected, send a request packet with the expected sequence number
     * and a message count up to and including the messages in the packet.
     * Invoke also the corresponding method on the status listener and stop
     * processing the packet any further.</p>
     *
     * <p>If the message count in the packet indicates the end of session,
     * invoke the corresponding method on the status listener.</p>
     *
     * <p>Iterate through the messages in the packet. Starting from the message
     * with the expected sequence number, invoke the message listener on each
     * message.</p>
     *
     * <p>Finally invoke the method on the status listener indicating
     * that a downstream packet was processed successfully.</p>
     *
     * @throws IOException if an I/O error occurs
     */
    public void receive() throws IOException {
        rxBuffer.clear();

        if (channel.receive(rxBuffer) == null)
            return;

        rxBuffer.flip();

        if (rxBuffer.remaining() < 20)
            throw truncatedPacket();

        rxBuffer.order(ByteOrder.BIG_ENDIAN);

        rxBuffer.get(session);

        long sequenceNumber = rxBuffer.getLong();
        int  messageCount   = getUnsignedShort(rxBuffer);

        if (sequenceNumber > expectedSequenceNumber) {
            int requestedMessageCount = (int)Math.min(sequenceNumber - expectedSequenceNumber + 1,
                    MAX_MESSAGE_COUNT);

            request(requestedMessageCount);
            return;
        }

        if (messageCount == MESSAGE_COUNT_END_OF_SESSION) {
            statusListener.endOfSession();
        } else {
            int actualMessageCount = 0;

            for (int i = 0; i < messageCount; i++) {
                if (rxBuffer.remaining() < 2)
                    throw truncatedPacket();

                rxBuffer.order(ByteOrder.BIG_ENDIAN);

                int messageLength = getUnsignedShort(rxBuffer);

                if (rxBuffer.remaining() < messageLength)
                    throw truncatedPacket();

                if (sequenceNumber + i < expectedSequenceNumber)
                    skip(messageLength);
                else
                    read(messageLength);

                actualMessageCount++;
            }

            expectedSequenceNumber += actualMessageCount;
        }

        statusListener.downstream();
    }

    private void request(int requestedMessageCount) throws IOException {
        txBuffer.clear();
        txBuffer.put(session);
        txBuffer.putLong(expectedSequenceNumber);
        putUnsignedShort(txBuffer, requestedMessageCount);
        txBuffer.flip();

        while (channel.send(txBuffer, requestAddress) == 0);

        statusListener.request(expectedSequenceNumber, requestedMessageCount);
    }

    private void read(int messageLength) throws IOException {
        int limit = rxBuffer.limit();

        rxBuffer.limit(rxBuffer.position() + messageLength);

        listener.message(rxBuffer);

        rxBuffer.position(rxBuffer.limit());
        rxBuffer.limit(limit);
    }

    private void skip(int messageLength) {
        rxBuffer.position(rxBuffer.position() + messageLength);
    }

    /**
     * Close the underlying datagram channel.
     *
     * @throws IOException if an I/O error occurs
     */
    @Override
    public void close() throws IOException {
        channel.close();
    }

    private MoldUDP64Exception truncatedPacket() {
        return new MoldUDP64Exception("Truncated packet");
    }

}