module.exports = {
  dependency: {
    platforms: {
      android: {
        sourceDir: 'android',
        packageImportPath: 'import com.maksbas.MaksbasPackage;',
        packageInstance: 'new MaksbasPackage()',
      },
      // Autolinking must not try to build an iOS target that doesn't exist.
      ios: null,
    },
  },
}
