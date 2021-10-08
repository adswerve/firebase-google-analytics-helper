# Analytics Helper Class for Firebase & Google Analytics
by [Adswerve](https://www.adswerve.com)

Sample code for an "AnalyticsHelper" class to assist with common analytics implementation needs when using Firebase and Google Analytics.

**While this code is fully functional and requires only minor tweaks to add it to your app, the code is primarily intended as an example, to show how you might implement your own helper class.** If you aren’t keen on using the whole class, you can lift whatever portions may be of assistance for your own implementation, or use it as a jumping off point for your own helper class. This AnalyticsHelper code provides the following features:

* Sets various user properties and other settings on start up
* Validates events and user properties against Firebase/GA4 rules, with various options for bringing errors to your attention
* Truncates event parameter and user property values to the maximum lengths supported
* Defines an illustrative list of constants for events, parameters, and user properties
* Adds common parameters (with current values) to all events
* Android: Automatically logs custom `app_open` and `app_close` events, with engagement time, based on Android’s application lifecycle
* iOS: Enables [DebugView](https://support.google.com/analytics/answer/7201382) in test builds not launched directly from Xcode (e.g., when using TestFlight)
* Universal Analytics: Provides code to send GA (UA) hits in the background (on iOS). Also sets the GA dispatch interval on test builds so that hits can be seen immediately during development and testing.

A quick note about the last item: While [Google Analytics 4](https://support.google.com/analytics/answer/10089681) is clearly the future of Google Analytics, use of Google’s GA360 “Universal Analytics” platform is still best practice for enterprise analytics, including app analytics, and will be for some time to come. But there are a few tricks to a UA app implementation, which our sample code helps with: Like the need to send any buffered hits when a production iOS app goes into the background (to prevent them from getting lost), and the need to set the dispatch interval to the minimum (one second) during testing on both platforms, so that hits are dispatched immediately rather than buffered. (By default, UA hits are buffered for two minutes on iOS and thirty minutes on Android.) _Note: If you implement the shorter dispatch interval, it’s important that you only do so in test builds—dispatching hits immediately in production could have a significant impact on device battery life._

## Implementation

#### Android
 Copy the **AnalyticsHelper.java** file or **AnalyticsHelper.kt** file from the repo folder to a convenient location in your Android Studio project. In the code, update the two package placeholders with the package name for your app.

#### iOS
For Swift apps, copy the **AnalyticsHelper.swift** file and **Analytics-Bridging-Header.h** files to a convenient location in your Xcode project. In the Build Settings for your app's target(s), add an entry for the bridging header in the **Swift Compiler - General** section (see [Apple's documentation](https://developer.apple.com/documentation/swift/imported_c_and_objective-c_apis/importing_objective-c_into_swift)).

For Objective-C apps, copy the **AAHAnalyticsHelper.h** and **AAHAnalyticsHelper.m** files to a convenient location in your Xcode project.

#### Both platforms
Assumption is that the Firebase Analytics SDK has been implemented per [Google's documentation](https://firebase.google.com/docs/analytics/get-started). Note that the parts of this sample code that deal with Universal Analytics depend on the presence of Google Tag Manager, which imports the Google Analytics library that provides access to the GoogleAnalytics (Android) or GAI (iOS) methods. (See [Android](https://developers.google.com/tag-manager/android/v5)/[iOS](https://developers.google.com/tag-manager/ios/v5) GTM documentation.) If you are not using GTM, and not sending hits to GA360 (Universal Analytics), you will need to remove the code that references GoogleAnalytics or GAI.

## Usage
On Android, call `AnalyticsHelper.configure(this)` from your app's Application#onCreate method on app startup. The AnalyticsHelper class must be initialized with an Android Context via the `configure` method before logging events, setting user properties, or calling any other methods that pass data to Firebase.

On iOS, in your App Delegate's application(\_:didFinishLaunchingWithOptions:) method, call `AnalyticsHelper.configure()` immediately after configuring Firebase. To enable DebugView when not launching the app from Xcode, call `AnalyticsHelper.setFirebaseLaunchArguments()` _before_ configuring Firebase. (By default, events are only sent to DebugView for test builds, but you can alter the behavior by modifying the `sendToDebugView` property.) To send buffered hits to Universal Analytics when the app enters the background, call `AnalyticsHelper.sendHitsInBackground()` from your App Delegate's applicationWillResignActive(_:) method.

All commonly-used Firebase methods have identically-named methods in these helpers based on language. For example, to log an event call `AnalyticsHelper.logEvent(eventName, params)` in Java or Kotlin, `AnalyticsHelper.logEvent(eventName, parameters: params)` in Swift, or `[AAHAnalyticsHelper logEventWithName:eventName parameters:params]` in Objective-C. Syntax is the same as calling the corresponding Firebase methods.

Validation behavior is controlled via the `validateInDebug`, `validateInProduction`, `sendValidationErrorEvents`, and `throwOnValidationErrorsInDebug` properties (Kotlin, Swift) or similarly-named setters (Java, Objective-C).

By default, event parameters and user properties are truncated to the maximum lengths supported (so that the values are not lost in Firebase/GA4), but this can be disabled by setting the `truncateStringValues` property to `false`.

Review the comments in the sample code for your app's language for additional customization options and usage tips.

## License
Created by Adswerve, Inc. and distributed under the BSD 3-Clause license. See `LICENSE` for more information.

## Contact
Ping us on [#measure](https://measure.slack.com/) or email us at analytics-help@adswerve.com if you have questions.

## Release History

* 0.1.0
  * Initial public version
