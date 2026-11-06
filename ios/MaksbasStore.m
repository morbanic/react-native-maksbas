#import "MaksbasStore.h"

static NSString *const kSuiteName = @"maksbas";
static NSString *const kEndpoint = @"endpoint";
static NSString *const kDeviceId = @"deviceId";
static NSString *const kDeviceSecret = @"deviceSecret";
static NSString *const kDebug = @"debug";
static NSString *const kPendingOpen = @"pendingOpen";
static NSString *const kQueuedEvents = @"queuedEvents";

/** Same cap as Android: an offline week must not grow the queue forever. */
static const NSUInteger kMaxQueuedEvents = 200;

@implementation MaksbasStore {
  NSUserDefaults *_defaults;
}

+ (instancetype)shared {
  static MaksbasStore *instance;
  static dispatch_once_t once;
  dispatch_once(&once, ^{
    instance = [MaksbasStore new];
  });
  return instance;
}

- (instancetype)init {
  if ((self = [super init])) {
    _defaults = [[NSUserDefaults alloc] initWithSuiteName:kSuiteName];
  }
  return self;
}

- (NSString *)endpoint { return [_defaults stringForKey:kEndpoint]; }
- (void)setEndpoint:(NSString *)value { [self setOrRemove:value forKey:kEndpoint]; }

- (NSString *)deviceId { return [_defaults stringForKey:kDeviceId]; }
- (void)setDeviceId:(NSString *)value { [self setOrRemove:value forKey:kDeviceId]; }

- (NSString *)deviceSecret { return [_defaults stringForKey:kDeviceSecret]; }
- (void)setDeviceSecret:(NSString *)value { [self setOrRemove:value forKey:kDeviceSecret]; }

- (BOOL)debug { return [_defaults boolForKey:kDebug]; }
- (void)setDebug:(BOOL)value { [_defaults setBool:value forKey:kDebug]; }

- (NSDictionary *)pendingOpen { return [_defaults dictionaryForKey:kPendingOpen]; }
- (void)setPendingOpen:(NSDictionary *)value { [self setOrRemove:value forKey:kPendingOpen]; }

- (void)clearCredentials {
  [_defaults removeObjectForKey:kDeviceId];
  [_defaults removeObjectForKey:kDeviceSecret];
}

- (NSString *)itemForKey:(NSString *)key {
  return [_defaults stringForKey:[self prefixed:key]];
}

- (void)setItem:(NSString *)value forKey:(NSString *)key {
  [_defaults setObject:value forKey:[self prefixed:key]];
}

- (void)removeItemForKey:(NSString *)key {
  [_defaults removeObjectForKey:[self prefixed:key]];
}

- (NSArray<NSDictionary *> *)queuedEvents {
  return [_defaults arrayForKey:kQueuedEvents] ?: @[];
}

- (void)queueEvent:(NSDictionary *)event {
  @synchronized (self) {
    NSMutableArray *queue = [[self queuedEvents] mutableCopy];
    [queue addObject:event];
    if (queue.count > kMaxQueuedEvents) {
      [queue removeObjectsInRange:NSMakeRange(0, queue.count - kMaxQueuedEvents)];
    }
    [_defaults setObject:queue forKey:kQueuedEvents];
  }
}

- (void)clearQueuedEvents {
  [_defaults removeObjectForKey:kQueuedEvents];
}

- (void)log:(NSString *)message {
  if (self.debug) NSLog(@"[Maksbas] %@", message);
}

#pragma mark - Private

- (NSString *)prefixed:(NSString *)key {
  return [@"kv:" stringByAppendingString:key];
}

- (void)setOrRemove:(id)value forKey:(NSString *)key {
  if (value) {
    [_defaults setObject:value forKey:key];
  } else {
    [_defaults removeObjectForKey:key];
  }
}

@end
