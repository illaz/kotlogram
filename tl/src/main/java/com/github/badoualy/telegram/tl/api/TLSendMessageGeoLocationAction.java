package com.github.badoualy.telegram.tl.api;

/**
 * @author Yannick Badoual yann.badoual@gmail.com
 * @see <a href="http://github.com/badoualy/kotlogram">http://github.com/badoualy/kotlogram</a>
 */
public class TLSendMessageGeoLocationAction extends TLAbsSendMessageAction {
    public static final int CONSTRUCTOR_ID = 0x176f8ba1;

    private final String _constructor = "sendMessageGeoLocationAction#176f8ba1";

    public TLSendMessageGeoLocationAction() {
    }

    @Override
    public String toString() {
        return _constructor;
    }

    @Override
    public int getConstructorId() {
        return CONSTRUCTOR_ID;
    }

    @Override
    @SuppressWarnings("PointlessBooleanExpression")
    public boolean equals(Object object) {
        if (!(object instanceof TLSendMessageGeoLocationAction)) return false;
        if (object == this) return true;

        TLSendMessageGeoLocationAction o = (TLSendMessageGeoLocationAction) object;

        return true;
    }
}
