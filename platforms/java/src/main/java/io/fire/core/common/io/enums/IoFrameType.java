package io.fire.core.common.io.enums;

import lombok.Getter;

public enum IoFrameType {

    /**
     * Frame types of the Fire-IO protocol
     */

    SINGLE((byte) 1),
    START((byte) 2),
    CONTINUE((byte) 3),
    FINISH((byte) 4),
    CONFIRM_PACKET((byte) 5),
    PING_PACKET((byte) 6),
    UNKNOWN((byte) -1);

    @Getter private byte contentByte;
    IoFrameType(byte b) {
        this.contentByte = b;
    }

    public static IoFrameType fromByte(byte input) {

        switch (input) {
            case 1:
                return SINGLE;
            case 2:
                return START;
            case 3:
                return CONTINUE;
            case 4:
                return FINISH;
            case 5:
                return CONFIRM_PACKET;
            case 6:
                return PING_PACKET;
            default:
                return UNKNOWN;
        }
    }

    public static IoFrameType fromBytes(byte[] input) {
        return fromByte(input[0]);
    }
}
