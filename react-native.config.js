module.exports = {
  dependency: {
    platforms: {
      android: {
        sourceDir: 'android',
        packageImportPath: 'import com.maksbas.MaksbasPackage;',
        packageInstance: 'new MaksbasPackage()',
      },
      // iOS is picked up from react-native-maksbas.podspec at the package root.
    },
  },
}
