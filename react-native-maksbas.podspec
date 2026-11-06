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
  s.dependency 'FirebaseMessaging', '~> 12.0'
end
