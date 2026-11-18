package backend.reminder;

import backend.db.ReminderDAO.Delivery;

@FunctionalInterface
public interface ReminderSender {
    void send(Delivery delivery) throws DeliveryFailure;

    final class DeliveryFailure extends Exception {
        public final boolean expiredSubscription;
        public DeliveryFailure(String safeMessage) { this(safeMessage, false); }
        public DeliveryFailure(String safeMessage, boolean expiredSubscription) {
            super(safeMessage);
            this.expiredSubscription = expiredSubscription;
        }
    }
}
