#import "MaksbasEventReporter.h"
#import "MaksbasStore.h"
#import <UIKit/UIKit.h>

@implementation MaksbasEventReporter

+ (void)reportNotification:(NSString *)notificationId type:(NSString *)type {
  NSDictionary *event = @{ @"notificationId" : notificationId, @"type" : type };
  MaksbasStore *store = [MaksbasStore shared];

  // A tap can be followed straight away by the user leaving the app again; ask
  // for a little time so the request isn't frozen halfway.
  __block UIBackgroundTaskIdentifier task = UIBackgroundTaskInvalid;
  dispatch_async(dispatch_get_main_queue(), ^{
    task = [UIApplication.sharedApplication beginBackgroundTaskWithName:@"maksbas.event"
                                                      expirationHandler:^{
      [UIApplication.sharedApplication endBackgroundTask:task];
      task = UIBackgroundTaskInvalid;
    }];

    [self send:@[ event ] completion:^(BOOL handled) {
      if (!handled) {
        [store queueEvent:event];
        [store log:[NSString stringWithFormat:@"queued %@ event for %@", type, notificationId]];
      }
      dispatch_async(dispatch_get_main_queue(), ^{
        if (task != UIBackgroundTaskInvalid) {
          [UIApplication.sharedApplication endBackgroundTask:task];
          task = UIBackgroundTaskInvalid;
        }
      });
    }];
  });
}

+ (void)flushWithCompletion:(void (^)(NSInteger))completion {
  MaksbasStore *store = [MaksbasStore shared];
  NSArray<NSDictionary *> *queue = [store queuedEvents];
  if (queue.count == 0) {
    completion(0);
    return;
  }

  // Clear first: a duplicate is a no-op on the server thanks to its unique
  // index, whereas a crash between send and clear would replay forever.
  [store clearQueuedEvents];

  [self send:queue completion:^(BOOL handled) {
    if (!handled) {
      for (NSDictionary *event in queue) [store queueEvent:event];
      completion(0);
      return;
    }
    completion((NSInteger)queue.count);
  }];
}

/** `handled` is true on 2xx and 4xx — a 4xx will never succeed on replay. */
+ (void)send:(NSArray<NSDictionary *> *)events completion:(void (^)(BOOL handled))completion {
  MaksbasStore *store = [MaksbasStore shared];
  NSString *endpoint = store.endpoint;
  NSString *deviceId = store.deviceId;
  NSString *secret = store.deviceSecret;

  if (!endpoint || !deviceId || !secret) {
    completion(NO);
    return;
  }

  NSString *path = [NSString stringWithFormat:@"%@/devices/%@/events", endpoint, deviceId];
  NSURL *url = [NSURL URLWithString:path];
  if (!url) {
    completion(YES);
    return;
  }

  NSMutableURLRequest *request = [NSMutableURLRequest requestWithURL:url];
  request.HTTPMethod = @"POST";
  request.timeoutInterval = 10;
  [request setValue:[@"Bearer " stringByAppendingString:secret] forHTTPHeaderField:@"Authorization"];
  [request setValue:@"application/json" forHTTPHeaderField:@"Content-Type"];
  request.HTTPBody = [NSJSONSerialization dataWithJSONObject:@{ @"events" : events } options:0 error:nil];

  [[NSURLSession.sharedSession dataTaskWithRequest:request
                                 completionHandler:^(NSData *data, NSURLResponse *response, NSError *error) {
    if (error) {
      [store log:[NSString stringWithFormat:@"event report failed: %@", error.localizedDescription]];
      completion(NO);
      return;
    }

    NSInteger status = [(NSHTTPURLResponse *)response statusCode];
    BOOL handled = (status >= 200 && status < 300) || (status >= 400 && status < 500);
    if (!handled) [store log:[NSString stringWithFormat:@"event report failed with HTTP %ld", (long)status]];
    completion(handled);
  }] resume];
}

@end
