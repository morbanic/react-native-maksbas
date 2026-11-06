#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

/**
 * NSUserDefaults-backed store, the iOS twin of MaksbasStore.kt.
 *
 * Its own suite, so the SDK needs no AsyncStorage dependency and a host app's
 * own keys can never collide with ours.
 */
@interface MaksbasStore : NSObject

+ (instancetype)shared;

@property (nonatomic, copy, nullable) NSString *endpoint;
@property (nonatomic, copy, nullable) NSString *deviceId;
@property (nonatomic, copy, nullable) NSString *deviceSecret;
@property (nonatomic, assign) BOOL debug;

/** The notification that cold-started the app, parked until JS asks for it. */
@property (nonatomic, copy, nullable) NSDictionary *pendingOpen;

- (void)clearCredentials;

- (nullable NSString *)itemForKey:(NSString *)key;
- (void)setItem:(NSString *)value forKey:(NSString *)key;
- (void)removeItemForKey:(NSString *)key;

- (NSArray<NSDictionary *> *)queuedEvents;
- (void)queueEvent:(NSDictionary *)event;
- (void)clearQueuedEvents;

- (void)log:(NSString *)message;

@end

NS_ASSUME_NONNULL_END
