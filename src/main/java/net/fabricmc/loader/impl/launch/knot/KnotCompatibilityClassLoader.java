/*
 * Copyright 2016 FabricMC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.fabricmc.loader.impl.launch.knot;

import java.net.URL;
import java.net.URLClassLoader;
import java.security.CodeSource;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.impl.game.GameProvider;
import net.fabricmc.loader.impl.launch.knot.KnotClassDelegate.ClassLoaderAccess;

// Paper - made public (was package-private) and reworked so this can be THE single classloader
// for the whole server process, not one of two. See KnotCompatibilityClassLoader(URL[], ClassLoader)
// below and ankhangbo.fabricloader.Pclip for how it's used: constructed ONCE, very early, with
// Paper's own full classpath already on it, before Fabric Loader/Knot/envType/GameProvider even
// exist — then reused (never replaced) as Knot's own loader once Knot.init() knows enough to wire
// up a real KnotClassDelegate on it. This is the only way to have truly one classloader instance:
// Knot's own normal code path (see KnotClassLoaderInterface.create()) unconditionally constructs a
// brand new loader object every time, which can never be literally the same object as anything
// built beforehand no matter how much cross-delegation is set up between the two.
public class KnotCompatibilityClassLoader extends URLClassLoader implements ClassLoaderAccess {
	private KnotClassDelegate<KnotCompatibilityClassLoader> delegate;

	KnotCompatibilityClassLoader(boolean isDevelopment, EnvType envType, GameProvider provider) {
		super(new URL[0], KnotCompatibilityClassLoader.class.getClassLoader());
		this.delegate = new KnotClassDelegate<>(isDevelopment, envType, this, getParent(), provider);
	}

	// Paper - construct with Paper's full real classpath already attached, and WITHOUT a delegate
	// yet (envType/GameProvider aren't known this early — see Pclip.main()). Until setDelegate() is
	// called, loadClass/findClass fall through to plain URLClassLoader behavior (super calls),
	// which is all this project's own early reflective bootstrapping (finding
	// FabricServerLauncher, Knot, etc.) needs.
	public KnotCompatibilityClassLoader(URL[] initialUrls, ClassLoader parent) {
		super("Knot", initialUrls, parent);
	}

	// Paper - wires up real Fabric Loader transformation/mixin/entrypoint logic onto THIS SAME,
	// already-in-use loader object, once Knot.init() knows envType/provider. Called at most once.
	void setDelegate(KnotClassDelegate<KnotCompatibilityClassLoader> delegate) {
		if (this.delegate != null) throw new IllegalStateException("delegate already set");
		this.delegate = delegate;
	}

	// Paper - reflective, package-visibility-bypassing addURL, used by Pclip to merge in this
	// agent's own (ASM-stripped) jar alongside Paper's real classpath at construction time.
	public void addUrl(URL url) {
		super.addURL(url);
	}

	KnotClassDelegate<?> getDelegate() {
		return delegate;
	}

	@Override
	protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
		if (Log4jScanGuard.shouldBlock(this, name)) throw new ClassNotFoundException(name); // Paper - see Log4jScanGuard
		EarlyLoadGuard.onLoadRequest(name); // Paper - see EarlyLoadGuard
		if (delegate == null) return super.loadClass(name, resolve);
		return delegate.loadClass(name, resolve);
	}

	@Override
	protected Class<?> findClass(String name) throws ClassNotFoundException {
		if (delegate == null) return super.findClass(name);
		return delegate.tryLoadClass(name, false);
	}

	@Override
	protected String findLibrary(String libname) {
		if (delegate == null) return super.findLibrary(libname);
		return delegate.findLibrary(libname);
	}

	@Override
	public void addUrlFwd(URL url) {
		super.addURL(url);
	}

	@Override
	public URL findResourceFwd(String name) {
		return findResource(name);
	}

	@Override
	public Package getPackageFwd(String name) {
		return super.getPackage(name);
	}

	@Override
	public Package definePackageFwd(String name, String specTitle, String specVersion, String specVendor,
			String implTitle, String implVersion, String implVendor, URL sealBase) throws IllegalArgumentException {
		return super.definePackage(name, specTitle, specVersion, specVendor, implTitle, implVersion, implVendor, sealBase);
	}

	@Override
	public Object getClassLoadingLockFwd(String name) {
		return super.getClassLoadingLock(name);
	}

	@Override
	public Class<?> findLoadedClassFwd(String name) {
		return super.findLoadedClass(name);
	}

	@Override
	public Class<?> defineClassFwd(String name, byte[] b, int off, int len, CodeSource cs) {
		return super.defineClass(name, b, off, len, cs);
	}

	@Override
	public void resolveClassFwd(Class<?> cls) {
		super.resolveClass(cls);
	}

	static {
		registerAsParallelCapable();
	}
}
