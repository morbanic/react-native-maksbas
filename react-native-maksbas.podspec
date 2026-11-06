require 'json'

package = JSON.parse(File.read(File.join(__dir__, 'package.json')))

Pod::Spec.new do |s|
  s.name         = 'react-native-maksbas'
  s.version      = package['version']
  s.summary      = package['description']
  s.license      = package['license']
  s.homepage     = 'https://github.com/morbanic/react-native-maksbas'
  s.authors      = 'Maksbas'
  s.platforms    = { :ios => '15.1' }
  s.source       = { :git => 'https://github.com/morbanic/react-native-maksbas.git', :tag => s.version.to_s }

  s.source_files = 'ios/**/*.{h,m}'

  s.dependency 'React-Core'

  # iOS goes through FCM like Android, so the server keeps one sender and one
  # token format. FCM hands the message to APNs; the APNs key lives in the
  # Firebase console, not here.
  #
  # Where Firebase comes from depends on the app. @react-native-firebase pulls
  # it through Swift Package Manager (Firebase is no longer published to
  # CocoaPods after 12.x), and a second, CocoaPods copy of FirebaseCore next to
  # it breaks the build. So when RNFirebase is installed, Messaging goes through
  # RNFirebase's own helper: same package, same pinned version, one Firebase.
  rnfb_app = File.join(__dir__, '..', '@react-native-firebase', 'app')
  rnfb_spm = File.join(rnfb_app, 'firebase_spm.rb')
  rnfb_spm_disabled = defined?($RNFirebaseDisableSPM) && $RNFirebaseDisableSPM == true

  if File.exist?(rnfb_spm) && !rnfb_spm_disabled
    require rnfb_spm
    firebase_version = defined?($FirebaseSDKVersion) && $FirebaseSDKVersion ||
      JSON.parse(File.read(File.join(rnfb_app, 'package.json')))['sdkVersions']['ios']['firebase']
    firebase_dependency(s, firebase_version, ['FirebaseMessaging'], 'Firebase/Messaging')
  else
    s.dependency 'FirebaseMessaging', '~> 12.0'
  end
end
