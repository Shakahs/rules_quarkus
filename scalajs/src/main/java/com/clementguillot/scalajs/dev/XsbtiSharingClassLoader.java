package com.clementguillot.scalajs.dev;

/**
 * The parent of the compiler's class loaders: the platform, plus this tool's {@code xsbti}.
 *
 * <p>The compiler is loaded apart from the tool, so that its standard library and zinc's never
 * meet. The one thing both sides must share is the {@code xsbti} interface: the compiler hands
 * zinc's {@code AnalysisCallback} through it, and a second copy of those classes on the
 * compiler's side would make that a {@code ClassCastException}. sbt parents its Scala loaders on
 * the loader that holds its own {@code xsbti} for the same reason; this does the same for one
 * package and delegates everything else to the platform.
 *
 * <p>The tool runs from a plain classpath — {@code java -cp}, as the rule and the test launch it
 * — so the application class loader is the one holding its {@code xsbti}.
 */
final class XsbtiSharingClassLoader extends ClassLoader {

  private static final String SHARED_PACKAGE = "xsbti.";

  private final ClassLoader tool;

  XsbtiSharingClassLoader(ClassLoader tool) {
    super(getPlatformClassLoader());
    this.tool = tool;
  }

  @Override
  protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
    if (name.startsWith(SHARED_PACKAGE)) {
      return tool.loadClass(name);
    }
    return super.loadClass(name, resolve);
  }
}
