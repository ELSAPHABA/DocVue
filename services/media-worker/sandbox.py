"""Linux-only launcher: prevent the untrusted decoder from using network syscalls."""
import ctypes
import errno
import os
import resource
import sys


def isolate():
    lib = ctypes.CDLL("libseccomp.so.2", use_errno=True)
    lib.seccomp_init.argtypes = [ctypes.c_uint32]
    lib.seccomp_init.restype = ctypes.c_void_p
    lib.seccomp_syscall_resolve_name.argtypes = [ctypes.c_char_p]
    lib.seccomp_syscall_resolve_name.restype = ctypes.c_int
    lib.seccomp_rule_add.argtypes = [ctypes.c_void_p, ctypes.c_uint32, ctypes.c_int, ctypes.c_uint]
    lib.seccomp_rule_add.restype = ctypes.c_int
    lib.seccomp_load.argtypes = [ctypes.c_void_p]
    lib.seccomp_load.restype = ctypes.c_int
    lib.seccomp_release.argtypes = [ctypes.c_void_p]
    ctx = lib.seccomp_init(0x7FFF0000)  # SCMP_ACT_ALLOW
    if not ctx:
        raise RuntimeError("cannot initialize decoder sandbox")
    try:
        # No inherited sockets; deny new sockets and all socket operations.
        for name in (b"socket", b"socketpair", b"socketcall", b"connect", b"bind",
                     b"listen", b"accept", b"accept4", b"sendto", b"recvfrom",
                     b"sendmsg", b"recvmsg", b"sendmmsg", b"recvmmsg",
                     b"io_uring_setup", b"ptrace", b"process_vm_readv", b"process_vm_writev"):
            number = lib.seccomp_syscall_resolve_name(name)
            if number == -1:
                continue  # Architecture does not implement this syscall.
            if lib.seccomp_rule_add(ctx, 0x00050000 | errno.EPERM, number, 0) != 0:
                raise RuntimeError("cannot configure decoder sandbox")
        if lib.seccomp_load(ctx) != 0:
            raise RuntimeError("cannot enforce decoder sandbox")
    finally:
        lib.seccomp_release(ctx)
    resource.setrlimit(resource.RLIMIT_CORE, (0, 0))
    resource.setrlimit(resource.RLIMIT_NOFILE, (128, 128))


if __name__ == "__main__":
    isolate()
    os.execvp(sys.argv[1], sys.argv[1:])
