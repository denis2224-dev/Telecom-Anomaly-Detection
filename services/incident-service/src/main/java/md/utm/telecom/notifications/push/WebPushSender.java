package md.utm.telecom.notifications.push;

public interface WebPushSender {
    int send(Target target, byte[] payload) throws Exception;

    record Target(String endpoint, String p256dh, String auth) {
        @Override
        public String toString() {
            return "PushTarget[redacted]";
        }
    }
}
