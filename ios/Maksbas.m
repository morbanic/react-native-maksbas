#import "Maksbas.h"
#import "MaksbasEventReporter.h"
#import "MaksbasStore.h"

#import <FirebaseMessaging/FirebaseMessaging.h>
#import <UIKit/UIKit.h>
#import <UserNotifications/UserNotifications.h>
#import <sys/utsname.h>

/** How long getFcmToken() waits for APNs to hand over a device token. */
static const NSTimeInterval kApnsTokenTimeout = 15;

@implementation Maksbas {
  BOOL _hasListeners;
}

RCT_EXPORT_MODULE(Maksbas)

+ (BOOL)requiresMainQueueSetup {
  return NO;
}

- (instancetype)init {
  if ((self = [super init])) {
    [MaksbasNotificationCenter shared].sink = self;
  }
  return self;
}

- (NSArray<NSString *> *)supportedEvents {
  return @[ @"maksbas:received", @"maksbas:opened", @"maksbas:tokenRefreshed" ];
}

- (void)startObserving {
  _hasListeners = YES;
}

- (void)stopObserving {
  _hasListeners = NO;
}

- (BOOL)emitEvent:(NSString *)name body:(NSDictionary *)body {
  if (!_hasListeners || !self.callableJSModules) return NO;
  [self sendEventWithName:name body:body];
  return YES;
}

- (NSDictionary *)constantsToExport {
  struct utsname info;
  uname(&info);
  NSString *machine = [NSString stringWithCString:info.machine encoding:NSUTF8StringEncoding];

  return @{
    // `iPhone16,2` rather than a marketing name: Apple publishes no lookup API,
    // and a hand-kept table goes stale with every release.
    @"model" : [NSString stringWithFormat:@"Apple %@", machine ?: UIDevice.currentDevice.model],
    @"language" : NSLocale.preferredLanguages.firstObject ?: @"",
    @"timezone" : NSTimeZone.localTimeZone.name ?: @"",
  };
}

RCT_EXPORT_METHOD(configure:(NSDictionary *)config
                  resolve:(RCTPromiseResolveBlock)resolve
                  reject:(RCTPromiseRejectBlock)reject) {
  MaksbasStore *store = [MaksbasStore shared];
  store.endpoint = [config[@"endpoint"] isKindOfClass:NSString.class] ? config[@"endpoint"] : nil;
  store.debug = [config[@"debug"] boolValue];
  // channelName, smallIcon and accentColor are Android-only: iOS draws the
  // notification itself, with the app icon.
  resolve(nil);
}

RCT_EXPORT_METHOD(setCredentials:(NSString *)deviceId
                  deviceSecret:(NSString *)deviceSecret
                  resolve:(RCTPromiseResolveBlock)resolve
                  reject:(RCTPromiseRejectBlock)reject) {
  MaksbasStore *store = [MaksbasStore shared];
  store.deviceId = deviceId;
  store.deviceSecret = deviceSecret;
  resolve(nil);
}

RCT_EXPORT_METHOD(clearCredentials:(RCTPromiseResolveBlock)resolve
                  reject:(RCTPromiseRejectBlock)reject) {
  [[MaksbasStore shared] clearCredentials];
  resolve(nil);
}

RCT_EXPORT_METHOD(getFcmToken:(RCTPromiseResolveBlock)resolve
                  reject:(RCTPromiseRejectBlock)reject) {
  if (![MaksbasNotificationCenter shared].firebaseReady) {
    reject(@"fcm_token_failed",
           @"Firebase is not configured. Add GoogleService-Info.plist to the app target "
           @"(Copy Bundle Resources) and rebuild.",
           nil);
    return;
  }

  dispatch_async(dispatch_get_main_queue(), ^{
    [self fetchTokenWaitingUntil:[NSDate dateWithTimeIntervalSinceNow:kApnsTokenTimeout]
                         resolve:resolve
                          reject:reject];
  });
}

/**
 * FCM refuses to mint a token before APNs has issued one, and APNs answers
 * asynchronously after registerForRemoteNotifications. Waiting here keeps that
 * ordering out of the JS API.
 */
- (void)fetchTokenWaitingUntil:(NSDate *)deadline
                       resolve:(RCTPromiseResolveBlock)resolve
                        reject:(RCTPromiseRejectBlock)reject {
  FIRMessaging *messaging = [FIRMessaging messaging];

  if (messaging.APNSToken == nil) {
    if (deadline.timeIntervalSinceNow <= 0) {
      reject(@"fcm_token_failed",
             @"No APNs token. Check the Push Notifications capability (aps-environment "
             @"entitlement) and the provisioning profile; on a simulator, use iOS 16+ on an "
             @"Apple silicon Mac.",
             nil);
      return;
    }
    dispatch_after(dispatch_time(DISPATCH_TIME_NOW, (int64_t)(0.25 * NSEC_PER_SEC)), dispatch_get_main_queue(), ^{
      [self fetchTokenWaitingUntil:deadline resolve:resolve reject:reject];
    });
    return;
  }

  [messaging tokenWithCompletion:^(NSString *token, NSError *error) {
    if (error || token.length == 0) {
      NSString *message = [NSString stringWithFormat:
          @"Could not get an FCM token. Check that the APNs key is uploaded in the Firebase "
          @"console for this bundle id. (%@)", error.localizedDescription ?: @"empty token"];
      reject(@"fcm_token_failed", message, error);
      return;
    }
    resolve(token);
  }];
}

RCT_EXPORT_METHOD(getPermissionStatus:(RCTPromiseResolveBlock)resolve
                  reject:(RCTPromiseRejectBlock)reject) {
  [UNUserNotificationCenter.currentNotificationCenter getNotificationSettingsWithCompletionHandler:^(UNNotificationSettings *settings) {
    resolve([Maksbas statusFrom:settings.authorizationStatus]);
  }];
}

/**
 * Asks for alert, sound and badge permission.
 *
 * iOS shows this dialog once. After the user answers, later calls return the
 * stored answer without any UI — which is why the SDK never fires it from
 * `init()`.
 */
RCT_EXPORT_METHOD(requestPermission:(RCTPromiseResolveBlock)resolve
                  reject:(RCTPromiseRejectBlock)reject) {
  UNAuthorizationOptions options = UNAuthorizationOptionAlert | UNAuthorizationOptionSound | UNAuthorizationOptionBadge;
  UNUserNotificationCenter *center = UNUserNotificationCenter.currentNotificationCenter;

  [center requestAuthorizationWithOptions:options completionHandler:^(BOOL granted, NSError *error) {
    if (granted) {
      dispatch_async(dispatch_get_main_queue(), ^{
        [UIApplication.sharedApplication registerForRemoteNotifications];
      });
    }
    [center getNotificationSettingsWithCompletionHandler:^(UNNotificationSettings *settings) {
      resolve([Maksbas statusFrom:settings.authorizationStatus]);
    }];
  }];
}

RCT_EXPORT_METHOD(getItem:(NSString *)key
                  resolve:(RCTPromiseResolveBlock)resolve
                  reject:(RCTPromiseRejectBlock)reject) {
  resolve([[MaksbasStore shared] itemForKey:key] ?: (id)NSNull.null);
}

RCT_EXPORT_METHOD(setItem:(NSString *)key
                  value:(NSString *)value
                  resolve:(RCTPromiseResolveBlock)resolve
                  reject:(RCTPromiseRejectBlock)reject) {
  [[MaksbasStore shared] setItem:value forKey:key];
  resolve(nil);
}

RCT_EXPORT_METHOD(removeItem:(NSString *)key
                  resolve:(RCTPromiseResolveBlock)resolve
                  reject:(RCTPromiseRejectBlock)reject) {
  [[MaksbasStore shared] removeItemForKey:key];
  resolve(nil);
}

/** Returns and clears the notification that cold-started the app. */
RCT_EXPORT_METHOD(getInitialNotification:(RCTPromiseResolveBlock)resolve
                  reject:(RCTPromiseRejectBlock)reject) {
  MaksbasStore *store = [MaksbasStore shared];
  NSDictionary *pending = store.pendingOpen;
  store.pendingOpen = nil;
  resolve(pending ?: (id)NSNull.null);
}

RCT_EXPORT_METHOD(flushPendingEvents:(RCTPromiseResolveBlock)resolve
                  reject:(RCTPromiseRejectBlock)reject) {
  [MaksbasEventReporter flushWithCompletion:^(NSInteger sent) {
    resolve(@(sent));
  }];
}

+ (NSString *)statusFrom:(UNAuthorizationStatus)status {
  switch (status) {
    case UNAuthorizationStatusAuthorized:
    case UNAuthorizationStatusProvisional:
    case UNAuthorizationStatusEphemeral:
      return @"granted";
    case UNAuthorizationStatusDenied:
      return @"denied";
    case UNAuthorizationStatusNotDetermined:
    default:
      return @"not_determined";
  }
}

@end
