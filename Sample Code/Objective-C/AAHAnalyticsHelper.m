/**
 * AnalyticsHelper.m
 *
 * Helper class to assist with common analytics implementation needs, including:
 *
 * - Defining event, parameter, and user property constants to prevent typos
 * - Adding standard parameters to every event, etc.
 * - Validating names/values before sending to Firebase
 * - Truncating string values to maximum supported lengths before sending to Firebase
 * - Enabling DebugView in builds not launched directly from Xcode
 * - Sending buffered hits when app goes into the background (Universal Analytics)
 *
 * This class does not need to  be instantiated. All methods are static class methods. For convenience,
 * all commonly-used Firebase methods have identically-named methods here.
 *
 * Firebase validation/enforcement behavior can be controlled via the following methods:
 *
 * - setValidateInDebug (default: true)
 * - setValidateInProduction (default: false)
 * - setSendValidationErrorEvents (default: false)
 * - setThrowOnValidationErrorsInDebug (default: false)
 * - setTruncateStringValues (default: true)
 *
 * This code is intended only to illustrate how you might create an analytics helper class.
 * It is not ready for production use as-is.
 *
 * The parts of this sample code that deal with Universal Analytics depend on the presence of
 * Google Tag Manager, which imports the Google Analytics library that provides access to the
 * GAI methods. If you are not using GTM, and not sending hits to GA360 (Universal Analytics),
 * you will need to remove code that references GAI.
 *
 * @author Chris Hubbard
 * @copyright Copyright (c) 2021 Adswerve. All rights reserved.
 */

#import "AAHAnalyticsHelper.h"
#import "GAI.h"  // loaded by Google Tag Manager
@import Firebase;

@implementation AAHAnalyticsHelper

// MARK: - Initializer

/**
 * Class initializer.
 */
+ (void) initialize {
    if (self == [AAHAnalyticsHelper class]) {
        // Once-only initializion for the class
        
        // Compile regex patterns for Firebase validation
        [self makeEventNameRegex];
        [self makeParameterNameRegex];
        [self makeUserPropertyNameRegex];
        
        // Note: Do not call configure here because this class may be
        // initialized before Firebase itself has been configured
    }
}

// MARK: - Configuration

/** Indicates whether this class has been configured. */
static BOOL _isConfigured = NO;

/** Ensures class is configured before use. */
+ (BOOL)isConfigured {
    if (!_isConfigured) {
        [self configure];
    }
    return _isConfigured;
}

/**
 * Configures this helper class and provides an opportunity to set initial state and refresh any user properties
 * that may have changed since the previous launch.
 *
 * This method will be called automatically if needed when using the Firebase helper methods below; however,
 * to set initial state as early as possible in the app's startup process, calling this manually immediately after
 * calling `[FIRApp configure]` is recommended.
 */
+ (void)configure {

    // refresh user properties that may have changed since last launch
    [FIRAnalytics setUserPropertyString:[self getTimezoneOffset] forName:kAAHAnalyticsHelperUserPropertyTimezoneOffset];     // example
    [FIRAnalytics setUserPropertyString:[FIRAnalytics appInstanceID] forName:kAAHAnalyticsHelperUserPropertyAppInstanceID];  // example
    
    // set an "environment" user property to allow GTM to send hits to the desired GA360 property (optional)
    // also allows test data to be filtered out in GA4/Firebase/BigQuery
    // Note: Due to the Firebase SDK's startup sequence, this user property may not be set until
    // after first_open and the first session_start, and potentially other automatic events that
    // occur immediately after the first launch. If this timing presents issues, the alternative
    // in traditional GA (Universal Analytics) is to use a GTM function call variable to supply
    // "environment" for each tag. (Currently there is no workaround for Firebase/GA4/BigQuery.)
    if([self isDebugBuild]) {
        // default implementation determines environment based on build type, but
        // the criteria can be made more sophisticated as needed
        [FIRAnalytics setUserPropertyString:@"test" forName:kAAHAnalyticsHelperUserPropertyEnvironment];
    } else {
        [FIRAnalytics setUserPropertyString:@"production" forName:kAAHAnalyticsHelperUserPropertyEnvironment];
    }
    
    // set GA dispatch interval (only applicable if using GTM to send data to Universal Analytics)
    [self setDispatchInterval];
    
    _isConfigured = YES;
}

// MARK: - Firebase helpers

/**
 * Wrapper for Firebase's logEventWithName method, providing an opportunity to validate the event, enforce Firebase rules,
 * append additional standard parameters, etc. before passing the event to Firebase.
 *
 * @param name The name of the event.
 * @param parameters Dictionary of event parameters (optional).
 */
+ (void)logEventWithName:(nonnull NSString *)name parameters:(nullable NSDictionary<NSString *, id> *)parameters
NS_SWIFT_NAME(logEvent(_:parameters:)) {
    
    if ([self isConfigured]) {
        // append additional parameters before logging the event (optional)
        // this could be done on every event, or just on certain events
        NSMutableDictionary *newParams = (nil == parameters) ? [[NSMutableDictionary alloc] init] : [parameters mutableCopy];
        [newParams setValue:[self getTimestamp] forKey:kAAHAnalyticsHelperParameterTimestamp]; // example: append timestamp parameter
        
        // validate event name and parameters before passing event to Firebase
        [self validateEventWithName:name parameters:newParams];
        
        // log updated event to Firebase Analytics
        [FIRAnalytics logEventWithName:name parameters: _truncateStringValues ? [self truncateParams:newParams] : newParams];
    } else {
        // pass directly to Firebase
        [FIRAnalytics logEventWithName:name parameters:parameters];
    }
}

/**
 * Wrapper for Firebase's setDefaultEventParameters method, providing an opportunity to validate the parameters
 * and enforce Firebase rules before passing them to Firebase.
 *
 * @param parameters Dictionary of event parameters (or nil to clear them).
 */
+ (void)setDefaultEventParameters:(nullable NSDictionary<NSString *,id> *)parameters {
    if ([self isConfigured]) {
        [self validateParameters:parameters source:@"default event parameters"];
        [FIRAnalytics setDefaultEventParameters:_truncateStringValues ? [self truncateParams:parameters] : parameters];
    } else {
        // pass directly to Firebase
        [FIRAnalytics setDefaultEventParameters:parameters];
    }
}

/**
 * Wrapper for Firebase's setUserPropertyString method, providing an opportunity to validate the user property
 * and enforce Firebase rules before passing it to Firebase.
 *
 * @param value The value of the user property (passing nil clears the user property).
 * @param name The name of the user property to set.
 */
+ (void)setUserPropertyString:(nullable NSString *)value forName:(nonnull NSString *)name
NS_SWIFT_NAME(setUserProperty(_:forName:)) {
    if([self isConfigured]) {
        [self validateUserProperty:value forName:name];
        [FIRAnalytics setUserPropertyString:(_truncateStringValues ? [self truncateUserProp:value] : value) forName:name];
    } else {
        // pass directly to Firebase
        [FIRAnalytics setUserPropertyString:value forName:name];
    }
}

/**
 * Wrapper for Firebase's setUserID method, providing an opportunity to validate the ID value before passing it to Firebase.
 *
 * This feature must be used in accordance with Google's Privacy Policy:
 * https://www.google.com/policies/privacy
 *
 * @param userID Value to set as the Firebase User ID (or nil to clear it).
 */
+ (void)setUserID:(nullable NSString*)userID {
    if([self isConfigured]) {
        [self validateUserID:userID];
        [FIRAnalytics setUserID:userID];
    } else {
        // pass directly to Firebase
        [FIRAnalytics setUserID:userID];
    }
}

/**
 * Wrapper for Firebase's setAnalyticsCollectionEnabled method. (For convenience.)
 *
 * Sets whether analytics collection is enabled for this app on this device. This setting is
 * persisted across app sessions. By default it is enabled.
 *
 * @param analyticsCollectionEnabled A flag that enables or disables Analytics collection.
 */
+ (void)setAnalyticsCollectionEnabled:(BOOL)analyticsCollectionEnabled {
    [FIRAnalytics setAnalyticsCollectionEnabled:analyticsCollectionEnabled];
}

/**
 * Wrapper for Firebase's resetAnalyticsData method. (For convenience.)
 *
 * Clears all analytics data for this instance from the device and resets the app instance ID.
 * FIRAnalyticsConfiguration values will be reset to the default values.
 */
+ (void)resetAnalyticsData {
    [FIRAnalytics resetAnalyticsData];
}

/**
 * Truncates string parameter value to maximum supported length.
 *
 * @param value String parameter value to evaluate.
 * @return As much of the string as will fit in an event parameter.
 */
+ (nullable NSString *)truncateParam:(nullable NSString *)value {
    if ([value length] <= kValidationParameterValueMaxLength) {
        return value;
    } else {
        return [value substringToIndex:kValidationParameterValueMaxLength];
    }
}

/**
 * Truncates user property value to maximum supported length.
 *
 * @param value User property value to evaluate.
 * @return As much of the string as will fit in a user property.
 */
+ (nullable NSString *)truncateUserProp:(nullable NSString *)value {
    if ([value length] <= kValidationUserPropertyValueMaxLength) {
        return value;
    } else {
        return [value substringToIndex:kValidationUserPropertyValueMaxLength];
    }
}

/**
 * Truncates string parameter values to maximum supported length.
 *
 * Because this iterates over the entire parameter dictionary, it's more efficient to use the
 * public truncateParam: method on just those string values that might exceed the max, but
 * this method can ensure overall compliance if desired.
 *
 * @param parameters Parameter dictionary to evaluate.
 * @return Dictionary with string values shortened (if needed).
 */
+ (nullable NSDictionary *)truncateParams:(nullable NSDictionary *)parameters {
    if (nil == parameters) {
        return parameters;
    } else {
        NSMutableDictionary *newParams = [NSMutableDictionary dictionary];
        [newParams setDictionary:parameters];
        for(id name in newParams.allKeys) {
            id value = [newParams valueForKey:name];
            if ([value isKindOfClass:[NSString class]] && [value length] > kValidationParameterValueMaxLength) {
                [newParams setValue:[value substringToIndex:kValidationParameterValueMaxLength] forKey:name];
            }
        }
        return newParams;
    }
}

// MARK: - Validation/enforcement of Firebase/GA4 rules

/** Firebase rules as defined at https://firebase.google.com/docs/reference/ios/firebaseanalytics/api/reference/Classes/FIRAnalytics */
static const int kValidationEventMaxParameters = 25;
static const int kValidationEventNameMaxLength = 40;
static const int kValidationParameterNameMaxLength = 40;
static const int kValidationParameterValueMaxLength = 100;
static const int kValidationUserPropertyNameMaxLength = 24;
static const int kValidationUserPropertyValueMaxLength = 36;
static const int kValidationUserIdValueMaxLength = 256;
static NSString *const kValidationEventNamePattern = @"^(?!ga_|google_|firebase_)[A-Za-z]{1}[A-Za-z0-9_]{0,%d}$";
static NSString *const kValidationParameterNamePattern = @"^(?!ga_|google_|firebase_)[A-Za-z]{1}[A-Za-z0-9_]{0,%d}$";
static NSString *const kValidationUserPropertyNamePattern = @"^(?!ga_|google_|firebase_)[A-Za-z]{1}[A-Za-z0-9_]{0,%d}$";

/** Private class variables for validation regex. */
static NSRegularExpression *_validEventNameRegex = nil;
static NSRegularExpression *_validParameterNameRegex = nil;
static NSRegularExpression *_validUserPropertyNameRegex = nil;

/**
 * Compiles regex pattern for Firebase event name validation using constants above.
 *
 * @throws NSInvalidArgumentException if pattern string is invalid. (Test builds only.)
 */
+ (void)makeEventNameRegex {
    NSError *err = nil;
    NSString *pattern = [NSString stringWithFormat:kValidationEventNamePattern, kValidationEventNameMaxLength-1];
    _validEventNameRegex = [NSRegularExpression regularExpressionWithPattern:pattern options:0 error:&err];
    if (nil != err && [self isDebugBuild]) {
        [[NSException exceptionWithName:NSInvalidArgumentException reason:@"Invalid regex pattern for Firebase event name validation" userInfo:nil] raise];
    }
}

/**
 * Compiles regex pattern for Firebase parameter name validation using constants above.
 *
 * @throws NSInvalidArgumentException if pattern string is invalid. (Test builds only.)
 */
+ (void)makeParameterNameRegex {
    NSError *err = nil;
    NSString *pattern = [NSString stringWithFormat:kValidationParameterNamePattern, kValidationParameterNameMaxLength-1];
    _validParameterNameRegex = [NSRegularExpression regularExpressionWithPattern:pattern options:0 error:&err];
    if (nil != err && [self isDebugBuild]) {
        [[NSException exceptionWithName:NSInvalidArgumentException reason:@"Invalid regex pattern for Firebase parameter name validation" userInfo:nil] raise];
    }
}

/**
 * Compiles regex pattern for Firebase user property name validation using constants above.
 *
 * @throws NSInvalidArgumentException if pattern string is invalid. (Test builds only.)
 */
+ (void)makeUserPropertyNameRegex {
    NSError *err = nil;
    NSString *pattern = [NSString stringWithFormat:kValidationUserPropertyNamePattern, kValidationUserPropertyNameMaxLength-1];
    _validUserPropertyNameRegex = [NSRegularExpression regularExpressionWithPattern:pattern options:0 error:&err];
    if (nil != err && [self isDebugBuild]) {
        [[NSException exceptionWithName:NSInvalidArgumentException reason:@"Invalid regex pattern for Firebase user property name validation" userInfo:nil] raise];
    }
}

/**
 * Indicates whether regex compiled successfully.
 *
 * @return True if regex patterns compiled.
 */
+ (BOOL)isRegexReady {
    return nil != _validEventNameRegex && nil != _validParameterNameRegex && nil != _validUserPropertyNameRegex;
}

/** Private class variables for validation/enforcement of Firebase rules (see setters below). */
static BOOL _validateInDebug = YES;
static BOOL _validateInProduction = NO;
static BOOL _throwOnValidationErrorsInDebug = NO;
static BOOL _sendValidationErrorEvents = NO;
static BOOL _truncateStringValues = YES;

/**
 * Controls whether Firebase validation is performed in debug builds. Default is true.
 */
+ (void)setValidateInDebug:(BOOL)enable { _validateInDebug = enable; }

/**
 * Controls whether Firebase validation in performed in release builds. Default is false.
 *
 * If enabled, only sends custom error events to Firebase, no logging or exceptions.
 */
+ (void)setValidateInProduction:(BOOL)enable { _validateInProduction = enable; }

/**
 * Controls whether custom validation error events are sent to Firebase. Default is false.
 */
+ (void)setSendValidationErrorEvents:(BOOL)enable { _sendValidationErrorEvents = enable; }

/**
 * Controls whether InvalidArgument exceptions are thrown for Firebase validation errors
 * in debug builds. Default is false.
 */
+ (void)setThrowOnValidationErrorsInDebug:(BOOL)enable { _throwOnValidationErrorsInDebug = enable; }

/**
 * Controls whether string values in event parameters and user properties are truncated to
 * the maximum lengths allowed before passing to Firebase. Default is true.
 *
 * While "validation" is about awareness of issues, this setting is about "enforcement", to
 * prevent Firebase from dropping parameters and user properties that exceed the allowable
 * lengths. If enabled, it applies regardless of build type or whether validation is enabled.
 *
 * Alternatively, use truncateParam: and truncateUserProp: to trim only those string values
 * that may potentially exceed the max.
 */
+ (void)setTruncateStringValues:(BOOL)enable { _truncateStringValues = enable; }

/**
 * Determines whether validation rules should be applied.
 *
 * @return True if validation should be applied.
 */
+ (BOOL)isValidationEnabled {
    return ((_validateInDebug && [self isDebugBuild]) || (_validateInProduction && ![self isDebugBuild])) && [self isRegexReady];
}

/**
 * If validation is enabled, checks the event name, parameter count, and parameter names and values against the Firebase/GA4 rules.
 *
 * See: https://firebase.google.com/docs/reference/ios/firebaseanalytics/api/reference/Classes/FIRAnalytics#logeventwithnameparameters
 *
 * @param name Name of the event.
 * @param parameters Dictionary of event parameters (optional).
 */
+ (void)validateEventWithName:(nonnull NSString*)name parameters:(nullable NSDictionary*)parameters {
    if ([self isValidationEnabled]) {
        // validate event name
        bool isInvalidName = [_validEventNameRegex numberOfMatchesInString:name options:0 range:NSMakeRange(0, [name length])] != 1;
        if (isInvalidName) {
            NSString *errorMessage = [NSString stringWithFormat:@"Invalid event name '%@'", name];
            [self handleValidationError:errorMessage];
        }
        // validate parameter count
        if (nil != parameters) {
            NSUInteger parameterCount = parameters.count;
            bool isInvalidCount = parameterCount > kValidationEventMaxParameters;
            if (isInvalidCount) {
                NSString *errorMessage = [NSString stringWithFormat:@"Too many parameters in event '%@': contains %ld, max %d", name, parameterCount, kValidationEventMaxParameters];
                [self handleValidationError:errorMessage];
            }
        }
        // validate parameters
        [self validateParameters:parameters source:name];
    }
}

/**
 * If validation is enabled, checks each event parameter name and value against the Firebase/GA4 rules.
 *
 * See: https://firebase.google.com/docs/reference/ios/firebaseanalytics/api/reference/Classes/FIRAnalytics#logeventwithnameparameters
 *
 * @param parameters Dictionary of event parameters (optional).
 * @param source Source of the parameters (for error message use).
 */
+ (void)validateParameters:(nullable NSDictionary*)parameters source:(nonnull NSString*)source {
    if (parameters && [self isValidationEnabled]) {
        for(id name in parameters.allKeys) {
            // validate parameter name
            bool isInvalidName = [_validParameterNameRegex numberOfMatchesInString:name options:0 range:NSMakeRange(0, [name length])] != 1;
            if (isInvalidName) {
                NSString *errorMessage = [NSString stringWithFormat:@"Invalid parameter name '%@' in '%@'", name, source];
                [self handleValidationError:errorMessage];
            }
            // validate parameter value
            id value = [parameters valueForKey:name];
            if ([name isEqualToString:@"items"]) {
                // special handling required for Ecommerce "items" parameter, which contains an array of products
                for (id product in value) {
                    if ([product isKindOfClass:[NSDictionary class]]) {
                        [self validateParameters:product source:[source stringByAppendingString:@" [items]"]];
                    }
                }
            } else {
                // normal event parameter
                bool isInvalidValue = [value isKindOfClass:[NSString class]] && [value length] > kValidationParameterValueMaxLength;
                if (isInvalidValue) {
                    NSString *errorMessage = [NSString stringWithFormat:@"Value too long for parameter '%@' in '%@': %@", name, source, value];
                    [self handleValidationError:errorMessage];
                }
            }
        }
    }
}

/**
 * If validation is enabled, checks the user property name and value against the Firebase/GA4 rules.
 *
 * See: https://firebase.google.com/docs/reference/ios/firebaseanalytics/api/reference/Classes/FIRAnalytics#setuserpropertystringforname
 *
 * @param value Value of the user property.
 * @param name Name of the user property
 */
+ (void)validateUserProperty:(nullable NSString*)value forName:(nonnull NSString*)name {
    if ([self isValidationEnabled]) {
        // validate user property name
        bool isInvalidName = [_validUserPropertyNameRegex numberOfMatchesInString:name options:0 range:NSMakeRange(0, [name length])] != 1;
        if (isInvalidName) {
            NSString *errorMessage = [NSString stringWithFormat:@"Invalid user property name '%@'", name];
            [self handleValidationError:errorMessage];
        }
        // validate user property value
        bool isInvalidValue = [value length] > kValidationUserPropertyValueMaxLength;
        if (isInvalidValue) {
            NSString *errorMessage = [NSString stringWithFormat:@"Value too long for user property '%@': %@", name, value];
            [self handleValidationError:errorMessage];
        }
    }
}

/**
 * If validation is enabled, checks the user ID against the Firebase/GA4 rules.
 *
 * See: https://firebase.google.com/docs/reference/ios/firebaseanalytics/api/reference/Classes/FIRAnalytics#setuserid
 *
 * @param userID Value of the user ID.
 */
+ (void)validateUserID:(nullable NSString*)userID {
    if (userID && [self isValidationEnabled]) {
        bool isInvalidValue = [userID length] > kValidationUserIdValueMaxLength;
        if (isInvalidValue) {
            NSString *errorMessage = [NSString stringWithFormat:@"User ID is too long: %@", userID];
            [self handleValidationError:errorMessage];
        }
    }
}

/**
 * Handles the validation error by logging it, sending a custom error event, and/or throwing an NSInvalidArgumentException exception.
 *
 * Behavior is controlled via the "validation" configuration properties. (Logging and exceptions are limited to debug builds only.)
 *
 * @param errorMessage The message to be communicated.message.
 * @throws NSInvalidArgumentException if exception option is enabled. (Test builds only.)
 */
+ (void)handleValidationError:(nonnull NSString*)errorMessage {
    // log error (debug builds only)
    if ([self isDebugBuild]) {
        NSLog(@"ERROR: %@", errorMessage);
    }
    // error event option (available in debug and production)
    if (_sendValidationErrorEvents) {
        if([errorMessage length] > kValidationParameterValueMaxLength) {
            // truncate message
            errorMessage = [NSString stringWithFormat:@"%@...",[errorMessage substringToIndex:kValidationParameterValueMaxLength-3]];
        }
        NSDictionary *errorParams = @{kAAHAnalyticsHelperParameterErrorMessage: errorMessage};
        [FIRAnalytics logEventWithName:kAAHAnalyticsHelperEventValidationError parameters:errorParams];
    }
    // NSInvalidArgumentException option (debug builds only)
    // note that the exception will prevent an error event (see above) from being sent
    if (_throwOnValidationErrorsInDebug && [self isDebugBuild]) {
        [[NSException exceptionWithName:NSInvalidArgumentException reason:errorMessage userInfo:nil] raise];
    }
    
}

// MARK: - Firebase/GA4 DebugView

/**
 * Indicates whether events should be sent to DebugView in the Firebase console and GA4 property.
 *
 * Default implementation is based on DEBUG flag, but you can customize this to enable DebugView for
 * select "release" builds that are used for testing. Do NOT enable for true production builds!
 *
 * To enable DebugView, call `[AAHAnalyticsHelper setFirebaseLaunchArguments]` before
 * calling `[FIRApp configure]`(see below).
 */
+ (BOOL)sendToDebugView {
    #if DEBUG
    return YES;
    #else
    return NO;
    #endif
}

/**
 * Forces the app to send Firebase events to the DebugView pane in the Firebase console or GA4 property.
 *
 * Useful for testing builds that are not launched directly from Xcode.
 *
 * Call this method from your AppDelegate's `application:didFinishLaunchingWithOptions:` method before
 * calling `[FIRApp configure]`.
 */
+ (void)setFirebaseLaunchArguments {
    if ([self sendToDebugView]) {
        NSMutableArray *newArguments = [NSMutableArray arrayWithArray:[[NSProcessInfo processInfo] arguments]];
        [newArguments addObject:@"-FIRAnalyticsDebugEnabled"];
        [newArguments addObject:@"-FIRDebugEnabled"];
        [newArguments addObject:@"-FIRAnalyticsVerboseLoggingEnabled"];
        [[NSProcessInfo processInfo] setValue:[newArguments copy] forKey:@"arguments"];
    }
}

// MARK: - Google Analytics (UA) dispatch

/**
 * Dispatch interval for traditional GA360 hits in production builds.
 *
 * FOR USE WITH GOOGLE TAG MANAGER, which imports the Google Analytics
 * library and exposes the GAI methods.
 *
 * By default hits are dispatched every 2 minutes when app is in the foreground.
 * Do not reduce or you risk excessive network activity and battery drain.
 */
static double const kGaProductionDispatchInterval = 120;  // seconds

/** Dispatch handler class variable for sending GA hits in the background. */
static void (^_dispatchHandler)(GAIDispatchResult result);

/**
 * Sets the dispatch interval for GA360 (Universal Analytics) hits sent by Google Tag Manager.
 *
 * FOR USE WITH GOOGLE TAG MANAGER, which imports the Google Analytics
 * library and exposes the GAI methods.
 *
 * The default GA dispatch interval for production apps is every 2 minutes (120 seconds), but
 * for debug builds it is helpful to set the interval to 1 second so hits go out immediately.
 */
+ (void)setDispatchInterval {
    if([self isDebugBuild]) {
        [GAI sharedInstance].dispatchInterval = 1;
        [GAI sharedInstance].logger.logLevel = kGAILogLevelVerbose;
    } else {
        [GAI sharedInstance].dispatchInterval = kGaProductionDispatchInterval;
    }
}

/**
 * Sends any queued hits to GA360 (Universal Analytics) when app enters the background.
 *
 * FOR USE WITH GOOGLE TAG MANAGER, which imports the Google Analytics
 * library and exposes the GAI methods.
 *
 * Call this method from your AppDelegate's `applicationWillResignActive:` method, or from
 * `sceneWillResignActive:` if using Scenes, before the app actually enters the background.
 * Based on: https://developers.google.com/analytics/devguides/collection/ios/v3/dispatch
 */
+ (void)sendHitsInBackground {
    __block BOOL taskExpired = NO;
    __block UIBackgroundTaskIdentifier taskId =
    [[UIApplication sharedApplication] beginBackgroundTaskWithExpirationHandler:^{
        taskExpired = YES;
    }];
    if (taskId == UIBackgroundTaskInvalid) {
        return;
    }
    _dispatchHandler = ^(GAIDispatchResult result) {
        // Send hits until no hits are left, a dispatch error occurs, or
        // the background task expires
        if (result == kGAIDispatchGood && !taskExpired) {
            [[GAI sharedInstance] dispatchWithCompletionHandler:_dispatchHandler];
        } else {
            // restore dispatch interval and end task
            [self setDispatchInterval];
            [[UIApplication sharedApplication] endBackgroundTask:taskId];
        }
    };
    [[GAI sharedInstance] dispatchWithCompletionHandler:_dispatchHandler];
}

// MARK: - Private convenience methods

/**
 * Indicates whether this is a debug build.
 */
+ (BOOL)isDebugBuild {
    #if DEBUG
    return YES;
    #else
    return NO;
    #endif
}

/**
 * Returns string representation of current timestamp.
 */
+ (NSString *)getTimestamp {
    NSDateFormatter *dateFormatter = [[NSDateFormatter alloc] init];
    [dateFormatter setDateFormat:@"yyyy-MM-dd HH:mm:ss.SSS 'GMT'Z '('z')'"];  // 2020-02-11 11:26:02.868 GMT-0800 (PST)
    return [dateFormatter stringFromDate:[NSDate date]];
}

/**
 * Returns the device's current timezone offset in hours vs GMT (e.g., -8.0, -7.0, 2.0, 1.0).
 */
+ (NSString *)getTimezoneOffset {
    double offsetInHours = [[NSTimeZone localTimeZone] secondsFromGMT] / 3600.0;
    return [NSString stringWithFormat: @"%1.1f", offsetInHours];
}

@end
