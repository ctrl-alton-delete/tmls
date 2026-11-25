/*
 * Replaces openbsd-compat/getrrsetbyname.c (build.sh copies it over). The original needs the
 * resolver's internals (struct __res_state, RES_INIT), which bionic does not have; Termux links
 * ldns instead. The only user is SSHFP host-key lookup (VerifyHostKeyDNS, off by default), so
 * a lookup that always fails is enough: ssh then checks host keys against known_hosts as usual.
 */
#include "includes.h"

#if !defined(HAVE_GETRRSETBYNAME) && !defined(HAVE_LDNS)

#include "getrrsetbyname.h"

int
getrrsetbyname(const char *hostname, unsigned int rdclass, unsigned int rdtype,
    unsigned int flags, struct rrsetinfo **res)
{
	(void)hostname; (void)rdclass; (void)rdtype; (void)flags;
	*res = NULL;
	return ERRSET_FAIL;
}

void
freerrset(struct rrsetinfo *rrset)
{
	(void)rrset;
}

#endif
