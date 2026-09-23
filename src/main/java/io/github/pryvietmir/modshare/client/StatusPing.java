package io.github.pryvietmir.modshare.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.SharedConstants;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Minimal server list ping (handshake + status request) that returns the raw status JSON,
 * because Minecraft's own pinger drops unknown fields such as {@code "modshare"}.
 */
public final class StatusPing {
    private static final int MAX_PACKET_SIZE = 2 * 1024 * 1024;

    private StatusPing() {}

    /** The {@code "modshare"} object of the server's status, or null if the server does not advertise ModShare. */
    public static @Nullable JsonObject queryModShare(ServerAddress address, InetSocketAddress resolved, int timeoutMs) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(resolved, timeoutMs);
            socket.setSoTimeout(timeoutMs);

            ByteArrayOutputStream handshake = new ByteArrayOutputStream();
            DataOutputStream data = new DataOutputStream(handshake);
            writeVarInt(data, 0x00); // handshake packet id
            writeVarInt(data, SharedConstants.getCurrentVersion().getProtocolVersion());
            byte[] host = address.getHost().getBytes(StandardCharsets.UTF_8);
            writeVarInt(data, host.length);
            data.write(host);
            data.writeShort(address.getPort());
            writeVarInt(data, 1); // next state: status

            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            writePacket(out, handshake.toByteArray());
            writePacket(out, new byte[]{0x00}); // status request
            out.flush();

            DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            int length = readVarInt(in);
            if (length <= 0 || length > MAX_PACKET_SIZE) throw new IOException("Bad status packet length " + length);
            byte[] packet = new byte[length];
            in.readFully(packet);

            DataInputStream body = new DataInputStream(new java.io.ByteArrayInputStream(packet));
            if (readVarInt(body) != 0x00) throw new IOException("Unexpected status packet");
            byte[] json = new byte[readVarInt(body)];
            body.readFully(json);

            JsonElement root = JsonParser.parseString(new String(json, StandardCharsets.UTF_8));
            if (root.isJsonObject() && root.getAsJsonObject().get("modshare") instanceof JsonObject info) return info;
            return null;
        } catch (RuntimeException e) {
            throw new IOException("Malformed status response", e);
        }
    }

    private static void writePacket(DataOutputStream out, byte[] packet) throws IOException {
        writeVarInt(out, packet.length);
        out.write(packet);
    }

    private static void writeVarInt(DataOutputStream out, int value) throws IOException {
        while ((value & ~0x7F) != 0) {
            out.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.writeByte(value);
    }

    private static int readVarInt(InputStream in) throws IOException {
        int value = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            int b = in.read();
            if (b == -1) throw new IOException("Connection closed");
            value |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) return value;
        }
        throw new IOException("VarInt too big");
    }
}
