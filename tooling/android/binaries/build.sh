#!/usr/bin/env bash
# Builds the OpenSSH client for Android (plan M1, spec ARCH-3) and writes it as
#   OUT/<abi>/libssh.so, libscp.so, libssh-keygen.so
# which the app ships in jniLibs (Android only lets an app exec files in its nativeLibraryDir,
# and only names lib*.so get there). Usage:
#   tooling/android/binaries/build.sh OUT [abi ...]        # abis: arm64-v8a x86_64 (default both)
# Needs: ANDROID_NDK_HOME (or ANDROID_HOME with ndk/$NDK_VERSION), curl, make, perl, patch,
# autoreconf. Inputs are pinned in VERSIONS.
#
# Static OpenSSL and zlib, no Kerberos, ldns or libedit: each binary depends only on bionic
# (libc, libdl, libm), so one file is one program and nothing needs patchelf (risk R3).
# Termux's openssh patches (from the pinned termux-packages commit) make it work on Android:
# ControlMaster without hard links (mux.c), config permission checks, paths.
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
# shellcheck source=VERSIONS
source "$here/VERSIONS"

out=${1:?usage: build.sh OUT [abi ...]}
shift
abis=("$@")
[ ${#abis[@]} -gt 0 ] || abis=(arm64-v8a x86_64)
mkdir -p "$out"
out=$(cd "$out" && pwd)

ndk=${ANDROID_NDK_HOME:-${ANDROID_HOME:?set ANDROID_NDK_HOME or ANDROID_HOME}/ndk/$NDK_VERSION}
[ -d "$ndk/toolchains/llvm/prebuilt/linux-x86_64" ] || { echo "no NDK at $ndk" >&2; exit 1; }
toolchain=$ndk/toolchains/llvm/prebuilt/linux-x86_64
export PATH="$toolchain/bin:$PATH"
export ANDROID_NDK_ROOT=$ndk

work=${TMLS_BINARIES_WORK:-$out/.work}
mkdir -p "$work/src"
jobs=$(nproc 2>/dev/null || echo 4)

fetch() { # url sha256 file
	local file=$work/src/$3
	if [ ! -f "$file" ] || ! echo "$2  $file" | sha256sum -c --quiet - 2>/dev/null; then
		curl -fsSL --retry 3 -o "$file.part" "$1"
		echo "$2  $file.part" | sha256sum -c --quiet -
		mv "$file.part" "$file"
	fi
}

fetch "$OPENSSH_URL" "$OPENSSH_SHA256" "openssh-$OPENSSH_VERSION.tar.gz"
fetch "$OPENSSL_URL" "$OPENSSL_SHA256" "openssl-$OPENSSL_VERSION.tar.gz"
fetch "$ZLIB_URL" "$ZLIB_SHA256" "zlib-$ZLIB_VERSION.tar.xz"

# Termux's openssh patches at the pinned commit (the list comes from GitHub's API, the files from
# raw URLs at that commit, so the set is exactly what that commit has).
patches=$work/src/termux-openssh-$TERMUX_PACKAGES_COMMIT
if [ ! -f "$patches/.done" ]; then
	mkdir -p "$patches"
	api=https://api.github.com/repos/termux/termux-packages/contents/packages/openssh?ref=$TERMUX_PACKAGES_COMMIT
	curl -fsSL --retry 3 "$api" | grep -o '"name": *"[^"]*\.patch"' | sed 's/.*"\([^"]*\)"$/\1/' > "$patches/list"
	[ -s "$patches/list" ] || { echo "no patch list from $api" >&2; exit 1; }
	while read -r name; do
		curl -fsSL --retry 3 -o "$patches/$name" \
			"https://raw.githubusercontent.com/termux/termux-packages/$TERMUX_PACKAGES_COMMIT/packages/openssh/$name"
	done < "$patches/list"
	touch "$patches/.done"
fi

prefix=$APP_DIR/usr

for abi in "${abis[@]}"; do
	case $abi in
		arm64-v8a) triple=aarch64-linux-android; openssl_target=android-arm64 ;;
		x86_64) triple=x86_64-linux-android; openssl_target=android-x86_64 ;;
		*) echo "unsupported abi $abi" >&2; exit 1 ;;
	esac
	cc="$toolchain/bin/$triple$ANDROID_API-clang"
	build=$work/$abi
	deps=$build/deps
	rm -rf "$build"
	mkdir -p "$build" "$deps"

	echo "== zlib $ZLIB_VERSION ($abi)"
	tar -xf "$work/src/zlib-$ZLIB_VERSION.tar.xz" -C "$build"
	(cd "$build/zlib-$ZLIB_VERSION" &&
		CC=$cc AR=llvm-ar RANLIB=llvm-ranlib CFLAGS="-O2 -fPIC" ./configure --static --prefix="$deps" >/dev/null &&
		make -j"$jobs" libz.a >/dev/null && make install >/dev/null)

	echo "== OpenSSL $OPENSSL_VERSION ($abi)"
	tar -xzf "$work/src/openssl-$OPENSSL_VERSION.tar.gz" -C "$build"
	(cd "$build/openssl-$OPENSSL_VERSION" &&
		./Configure "$openssl_target" --prefix="$deps" --libdir=lib \
			no-shared no-module no-engine no-tests no-docs no-apps no-ssl no-hw no-srp no-dso \
			-DNO_SYSLOG >/dev/null &&
		make -j"$jobs" build_libs >/dev/null && make install_dev >/dev/null)

	echo "== OpenSSH $OPENSSH_VERSION ($abi)"
	tar -xzf "$work/src/openssh-$OPENSSH_VERSION.tar.gz" -C "$build"
	src=$build/openssh-$OPENSSH_VERSION
	while read -r name; do
		sed -e "s%@TERMUX_PREFIX@%$prefix%g" -e "s%@TERMUX_HOME@%$APP_DIR/home%g" "$patches/$name" |
			patch -d "$src" -p1 --quiet --forward
	done < "$patches/list"
	cp "$here/getrrsetbyname-stub.c" "$src/openbsd-compat/getrrsetbyname.c"
	(cd "$src" && autoreconf -fi >/dev/null 2>&1 &&
		CC=$cc LD=$cc AR=llvm-ar RANLIB=llvm-ranlib STRIP=llvm-strip \
		CFLAGS="-O2 -fPIE" \
		CPPFLAGS="-DHAVE_ATTRIBUTE__SENTINEL__=1 -DBROKEN_SETRESGID -DTMLS_HOME_PATH=$APP_DIR/home" \
		LDFLAGS="-pie -Wl,-z,max-page-size=16384" \
		./configure --host="$triple" \
			--prefix="$prefix" --bindir="$APP_DIR/bin" --sysconfdir="$prefix/etc/ssh" \
			--with-ssl-dir="$deps" --with-zlib="$deps" \
			--without-ldns --without-libedit --without-kerberos5 --without-pam \
			--without-security-key-builtin --disable-security-key \
			--disable-etc-default-login --disable-lastlog --disable-libutil \
			--disable-pututline --disable-pututxline --disable-strip \
			--disable-utmp --disable-utmpx --disable-wtmp --disable-wtmpx \
			--with-cflags=-Dfd_mask=int --without-stackprotect \
			--with-pid-dir="$prefix/var/run" --with-privsep-path="$prefix/var/empty" \
			--with-default-path="$APP_DIR/bin:/system/bin" \
			ac_cv_func_endgrent=yes ac_cv_func_fmt_scaled=no ac_cv_func_getlastlogxbyname=no \
			ac_cv_func_readpassphrase=no ac_cv_func_strnvis=no ac_cv_header_sys_un_h=yes \
			ac_cv_lib_crypt_crypt=no ac_cv_search_getrrsetbyname=no ac_cv_func_bzero=yes \
			ac_cv_member_struct_passwd_pw_gecos=no \
			ac_cv_func_calloc_0_nonnull=yes ac_cv_func_malloc_0_nonnull=yes ac_cv_func_realloc_0_nonnull=yes \
			>"$build/configure.log" 2>&1 ||
		{ tail -40 "$build/configure.log"; exit 1; })
	# The pwd shim only now: force-included during configure it breaks the function checks
	# (configure declares e.g. getline itself and the shim's headers then conflict).
	sed -i "s%^CPPFLAGS=%CPPFLAGS=-include $here/tmls_pwd.h %" "$src/Makefile" "$src/openbsd-compat/Makefile"
	grep -q "include $here/tmls_pwd.h" "$src/Makefile" || { echo "pwd shim not in Makefile" >&2; exit 1; }
	(cd "$src" && make -j"$jobs" ssh scp ssh-keygen >"$build/make.log" 2>&1 || { tail -60 "$build/make.log"; exit 1; })

	mkdir -p "$out/$abi"
	for program in ssh scp ssh-keygen; do
		llvm-strip -o "$out/$abi/lib$program.so" "$src/$program"
	done
	ls -l "$out/$abi"
done
