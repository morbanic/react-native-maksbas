#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

/**
 * Reports `delivered` and `opened` straight from native code, like
 * EventReporter.kt. Failures are queued and replayed by `flush` on the next
 * start instead of retried in place.
 */
@interface MaksbasEventReporter : NSObject

+ (void)reportNotification:(NSString *)notificationId type:(NSString *)type;

/** Replays the queue and calls back with the number of events sent. */
+ (void)flushWithCompletion:(void (^)(NSInteger sent))completion;

@end

NS_ASSUME_NONNULL_END
