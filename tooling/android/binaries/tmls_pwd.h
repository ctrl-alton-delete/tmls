/*
 * Force-included into every OpenSSH source file (-include). Android's getpwuid() gives an app
 * a home of "/" or "/data" and a shell that is not ours, and OpenSSH takes ~ from pw_dir, so
 * ~/.ssh/config, keys and known_hosts would be looked up in the wrong place (plan M1 check 5).
 *
 * Termux solves this by patching the NDK's pwd.h (ndk-patches/<ver>/pwd.h.patch) to return its
 * fixed home. This is the same idea, but $HOME wins when set, so the app decides where home is
 * at run time (spec ARCH-6: $APP/home); TMLS_HOME (TMLS_HOME_PATH from -D) is only the fallback.
 */
#ifndef TMLS_PWD_H
#define TMLS_PWD_H

#include <pwd.h>
#include <stdlib.h>

/* TMLS_HOME_PATH comes unquoted from -D (quotes do not survive configure's CPPFLAGS). */
#define TMLS_STR_(x) #x
#define TMLS_STR(x) TMLS_STR_(x)
#define TMLS_HOME TMLS_STR(TMLS_HOME_PATH)

static inline void tmls_setup_pwd(struct passwd *pw) {
	const char *home = getenv("HOME");
	pw->pw_dir = (char *)((home != NULL && home[0] == '/') ? home : TMLS_HOME);
	pw->pw_shell = (char *)"/system/bin/sh";
	pw->pw_passwd = (char *)"*";
#ifdef __LP64__
	pw->pw_gecos = (char *)"";
#endif
}

static inline struct passwd *tmls_getpwuid(uid_t uid) {
	struct passwd *pw = getpwuid(uid);
	if (pw != NULL) tmls_setup_pwd(pw);
	return pw;
}

static inline struct passwd *tmls_getpwnam(const char *name) {
	struct passwd *pw = getpwnam(name);
	if (pw != NULL) tmls_setup_pwd(pw);
	return pw;
}

static inline int tmls_getpwuid_r(uid_t uid, struct passwd *pwd, char *buf, size_t len, struct passwd **result) {
	int ret = getpwuid_r(uid, pwd, buf, len, result);
	if (ret == 0 && *result != NULL) tmls_setup_pwd(pwd);
	return ret;
}

#define getpwuid tmls_getpwuid
#define getpwnam tmls_getpwnam
#define getpwuid_r tmls_getpwuid_r

#endif
