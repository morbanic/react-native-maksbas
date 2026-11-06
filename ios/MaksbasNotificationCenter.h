#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

/** Receives payloads from the notification center; implemented by the module. */
@protocol MaksbasEventSink <NSObject>
/** Returns NO when no JS listener is attached, so the caller can park it. */
- (BOOL)emitEvent:(NSString *)name body:(NSDictionary *)body;
@end

/**
 * Owns everything that has to exist before JavaScript does.
 *
 * Sets itself up from `+load`, on UIApplicationDidFinishLaunchingNotification,
 * so the host app's AppDelegate needs no edits: Firebase is configured, the
 * UNUserNotificationCenter delegate is installed, and APNs registration is
 * started. The delegate has to be in place by the end of launch — a tap that
 * cold-starts the app is delivered to whoever holds it then, long before any JS
 * listener exists.
 */
@interface MaksbasNotificationCenter : NSObject

+ (instancetype)shared;

@property (nonatomic, weak, nullable) id<MaksbasEventSink> sink;

/** True once Firebase is configured, i.e. GoogleService-Info.plist was found. */
@property (nonatomic, readonly) BOOL firebaseReady;

/** Payload in the JS shape (see `MaksbasNotification`), or nil if not ours. */
+ (nullable NSDictionary *)payloadFromUserInfo:(NSDictionary *)userInfo;

@end

NS_ASSUME_NONNULL_END
