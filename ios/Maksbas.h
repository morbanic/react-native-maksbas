#import <React/RCTEventEmitter.h>
#import "MaksbasNotificationCenter.h"

/** Same surface as MaksbasModule.kt — see `MaksbasNativeModule` in src/native.ts. */
@interface Maksbas : RCTEventEmitter <MaksbasEventSink>
@end
