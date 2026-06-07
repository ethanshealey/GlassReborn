import Foundation
import CallKit
import Combine

/** Monitors phone calls via CXCallObserver and forwards them to Glass. */
final class CallObserver: NSObject, CXCallObserverDelegate, ObservableObject {

    @Published var activeCall: CXCall?

    private let observer = CXCallObserver()
    private let contacts = CNContactBridge()
    var onCallStart: ((String, String) -> Void)?
    var onCallEnd:   (() -> Void)?

    override init() {
        super.init()
        observer.setDelegate(self, queue: .main)
    }

    func callObserver(_ callObserver: CXCallObserver, callChanged call: CXCall) {
        if call.hasEnded {
            activeCall = nil
            onCallEnd?()
            return
        }
        if !call.isOutgoing && call.isOnHold == false {
            activeCall = call
            // Try to resolve the caller name from Contacts
            contacts.lookupNumber(call.uuid.uuidString) { [weak self] name in
                self?.onCallStart?(name ?? "Unknown", "")
            }
        }
    }
}

/** Minimal Contacts bridge — resolves recent call info when available. */
private class CNContactBridge {
    func lookupNumber(_ uuid: String, completion: @escaping (String?) -> Void) {
        // CXCallObserver doesn't give us the phone number directly.
        // In production, use CallKit provider entitlements for full caller ID.
        // For now, return nil and let the UI show "Incoming Call".
        completion(nil)
    }
}
