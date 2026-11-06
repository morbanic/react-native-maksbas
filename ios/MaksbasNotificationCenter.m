#import "MaksbasNotificationCenter.h"
#import "MaksbasEventReporter.h"
#import "MaksbasStore.h"

#import <FirebaseCore/FirebaseCore.h>
#import <FirebaseMessaging/FirebaseMessaging.h>
#import <UIKit/UIKit.h>
#import <UserNotifications/UserNotifications.h>

@interface MaksbasNotificationCenter () <UNUserNotificationCenterDelegate, FIRMessagingDelegate>
@end

@implementation MaksbasNotificationCenter {
  /** Whoever held the delegate before us — another SDK, or the app itself. */
  __weak id<UNUserNotificationCenterDelegate> _previousDelegate;
}

+ (void)load {
  [NSNotificationCenter.defaultCenter addObserverForName:UIApplicationDidFinishLaunchingNotification
                                                  object:nil
                                                   queue:NSOperationQueue.mainQueue
                                              usingBlock:^(NSNotification *note) {
    [[MaksbasNotificationCenter shared] setUp];
  }];
}

+ (instancetype)shared {
  static MaksbasNotificationCenter *instance;
  static dispatch_once_t once;
  dispatch_once(&once, ^{
    instance = [MaksbasNotificationCenter new];
  });
  return instance;
}

- (void)setUp {
  MaksbasStore *store = [MaksbasStore shared];

  if ([FIRApp defaultApp] == nil) {
    if ([NSBundle.mainBundle pathForResource:@"GoogleService-Info" ofType:@"plist"] == nil) {
      // Not a crash: the rest of the app has to keep working while push is
      // being set up. getFcmToken() reports this to JS instead.
      NSLog(@"[Maksbas] GoogleService-Info.plist is missing from the app bundle — push is disabled");
      return;
    }
    [FIRApp configure];
  }
  _firebaseReady = YES;

  [FIRMessaging messaging].delegate = self;

  UNUserNotificationCenter *center = UNUserNotificationCenter.currentNotificationCenter;
  if (center.delegate != self) {
    _previousDelegate = center.delegate;
    center.delegate = self;
  }

  // Allowed without the alert permission; the token arrives either way and
  // alerts start showing once the user grants it.
  [UIApplication.sharedApplication registerForRemoteNotifications];

  [store log:@"notification center ready"];
}

+ (NSDictionary *)payloadFromUserInfo:(NSDictionary *)userInfo {
  NSString *notificationId = [self string:userInfo[@"mb_id"]];
  if (!notificationId) return nil;

  // The server puts title and body in `aps.alert` for display and keeps the
  // `mb_` keys as data; the data keys are the contract, `aps` is a fallback.
  NSDictionary *alert = [userInfo[@"aps"] isKindOfClass:NSDictionary.class] ? userInfo[@"aps"][@"alert"] : nil;
  if (![alert isKindOfClass:NSDictionary.class]) alert = nil;

  NSString *title = [self string:userInfo[@"mb_title"]] ?: [self string:alert[@"title"]] ?: @"";
  NSString *body = [self string:userInfo[@"mb_body"]] ?: [self string:alert[@"body"]] ?: @"";

  NSDictionary *extra = @{};
  NSString *rawData = [self string:userInfo[@"mb_data"]];
  if (rawData) {
    id parsed = [NSJSONSerialization JSONObjectWithData:[rawData dataUsingEncoding:NSUTF8StringEncoding]
                                                options:0
                                                  error:nil];
    if ([parsed isKindOfClass:NSDictionary.class]) {
      NSMutableDictionary *strings = [NSMutableDictionary dictionary];
      [(NSDictionary *)parsed enumerateKeysAndObjectsUsingBlock:^(id key, id value, BOOL *stop) {
        strings[[key description]] = [value isKindOfClass:NSString.class] ? value : [value description];
      }];
      extra = strings;
    }
  }

  return @{
    @"id" : notificationId,
    @"title" : title,
    @"body" : body,
    @"image" : [self string:userInfo[@"mb_image"]] ?: NSNull.null,
    @"deeplink" : [self string:userInfo[@"mb_deeplink"]] ?: NSNull.null,
    @"data" : extra,
  };
}

+ (NSString *)string:(id)value {
  return [value isKindOfClass:NSString.class] && [value length] > 0 ? value : nil;
}

#pragma mark - UNUserNotificationCenterDelegate

/** A push while the app is in the foreground. */
- (void)userNotificationCenter:(UNUserNotificationCenter *)center
       willPresentNotification:(UNNotification *)notification
         withCompletionHandler:(void (^)(UNNotificationPresentationOptions))completionHandler {
  NSDictionary *payload = [MaksbasNotificationCenter payloadFromUserInfo:notification.request.content.userInfo];

  if (!payload) {
    id<UNUserNotificationCenterDelegate> previous = _previousDelegate;
    if ([previous respondsToSelector:_cmd]) {
      [previous userNotificationCenter:center willPresentNotification:notification withCompletionHandler:completionHandler];
    } else {
      completionHandler(UNNotificationPresentationOptionNone);
    }
    return;
  }

  [[MaksbasStore shared] log:[NSString stringWithFormat:@"received %@", payload[@"id"]]];
  [MaksbasEventReporter reportNotification:payload[@"id"] type:@"delivered"];
  [self.sink emitEvent:@"maksbas:received" body:payload];

  // Shown like Android does: the banner appears whether or not the app is open.
  completionHandler(UNNotificationPresentationOptionBanner | UNNotificationPresentationOptionList |
                    UNNotificationPresentationOptionSound);
}

/** A tap — from the foreground, the background, or one that cold-started the app. */
- (void)userNotificationCenter:(UNUserNotificationCenter *)center
    didReceiveNotificationResponse:(UNNotificationResponse *)response
             withCompletionHandler:(void (^)(void))completionHandler {
  NSDictionary *payload =
      [MaksbasNotificationCenter payloadFromUserInfo:response.notification.request.content.userInfo];

  if (!payload) {
    id<UNUserNotificationCenterDelegate> previous = _previousDelegate;
    if ([previous respondsToSelector:_cmd]) {
      [previous userNotificationCenter:center didReceiveNotificationResponse:response withCompletionHandler:completionHandler];
    } else {
      completionHandler();
    }
    return;
  }

  // Swiping a notification away is not an open.
  if (![response.actionIdentifier isEqualToString:UNNotificationDismissActionIdentifier]) {
    [[MaksbasStore shared] log:[NSString stringWithFormat:@"opened %@", payload[@"id"]]];
    [MaksbasEventReporter reportNotification:payload[@"id"] type:@"opened"];

    // If JS is listening it gets the event now. If the tap is what launched the
    // process, nothing is yet — park it for getInitialNotification(). Routing
    // the deeplink is left to JS on both paths, as on Android.
    BOOL delivered = [self.sink emitEvent:@"maksbas:opened" body:payload];
    if (!delivered) [MaksbasStore shared].pendingOpen = payload;
  }

  completionHandler();
}

- (void)userNotificationCenter:(UNUserNotificationCenter *)center
    openSettingsForNotification:(UNNotification *)notification {
  id<UNUserNotificationCenterDelegate> previous = _previousDelegate;
  if ([previous respondsToSelector:_cmd]) {
    [previous userNotificationCenter:center openSettingsForNotification:notification];
  }
}

#pragma mark - FIRMessagingDelegate

/**
 * FCM issues a new token on reinstall, restore, and occasionally on its own.
 * Without forwarding it the server keeps a retired token and every send to
 * this device fails silently.
 */
- (void)messaging:(FIRMessaging *)messaging didReceiveRegistrationToken:(NSString *)fcmToken {
  if (!fcmToken) return;
  [[MaksbasStore shared] log:@"FCM token received"];
  [self.sink emitEvent:@"maksbas:tokenRefreshed" body:@{ @"token" : fcmToken }];
}

@end
