#!/bin/sh
export LC_ALL=C
export LANG=C
ROOTFS_DIR=$(pwd)
export PATH=$PATH:~/.local/usr/bin

ARCH=$(uname -m)
case "$ARCH" in
    x86_64) ARCH_ALT=amd64 ;;
    aarch64) ARCH_ALT=arm64 ;;
    *) printf "Unsupported CPU: ${ARCH}\n"; exit 1 ;;
esac

# Extract rootfs if not installed
if [ ! -e $ROOTFS_DIR/.installed ]; then
    echo "###################################################################"
    echo "#              Proot INSTALLER - Copyright (C) 2024-2026          #"
    echo "###################################################################"
    echo ""
    echo "[*] Extracting Ubuntu rootfs..."

    # Extract from pre-downloaded file
    if [ -f /tmp/rootfs.tar.gz ]; then
        tar -xf /tmp/rootfs.tar.gz -C "$ROOTFS_DIR" 2>/dev/null
        if [ $? -ne 0 ]; then
            echo "Error: Failed to extract rootfs"
            exit 1
        fi
        rm -f /tmp/rootfs.tar.gz
        echo "[+] Rootfs extracted successfully"
    else
        echo "Error: Rootfs file not found at /tmp/rootfs.tar.gz"
        exit 1
    fi

    # Setup DNS
    printf "nameserver 1.1.1.1\nnameserver 1.0.0.1\n" > ${ROOTFS_DIR}/etc/resolv.conf

    # Mark as installed
    touch $ROOTFS_DIR/.installed
    echo "[+] Installation complete"
fi

# Configure hostname
echo "node" > $ROOTFS_DIR/etc/hostname
cat > $ROOTFS_DIR/etc/hosts << 'HOSTS_EOF'
127.0.0.1   localhost
127.0.1.1   node
::1         localhost ip6-localhost ip6-loopback
ff02::1     ip6-allnodes
ff02::2     ip6-allrouters
HOSTS_EOF

# Create autorun script
cat > $ROOTFS_DIR/root/.autorun.sh << 'AUTORUN_EOF'
#!/bin/bash
[ -f /root/.runlist ] && while read -r cmd; do
    [ -n "$cmd" ] && eval "$cmd" &
done < /root/.runlist
AUTORUN_EOF
chmod +x $ROOTFS_DIR/root/.autorun.sh

# Create bashrc
cat > $ROOTFS_DIR/root/.bashrc << 'BASHRC_EOF'
export HOSTNAME=node
export PS1='root@node:\w\$ '
export LC_ALL=C
export LANG=C
export TMOUT=0
unset TMOUT
alias ls='ls --color=auto'
alias ll='ls -lah'
alias grep='grep --color=auto'
[ -f /root/.autorun.sh ] && [ ! -f /tmp/.ran ] && (/root/.autorun.sh; touch /tmp/.ran)
run() {
    case "$1" in
        add)
            shift
            echo "$@" >> /root/.runlist
            echo "Added: $@"
            ;;
        rm|remove)
            shift
            grep -v "^$*$" /root/.runlist > /tmp/.runlist.tmp 2>/dev/null
            mv /tmp/.runlist.tmp /root/.runlist 2>/dev/null
            echo "Removed: $@"
            ;;
        list|ls)
            [ -f /root/.runlist ] && cat -n /root/.runlist || echo "Empty"
            ;;
        now)
            rm -f /tmp/.ran
            /root/.autorun.sh
            touch /tmp/.ran
            echo "Executed all commands"
            ;;
        *)
            [ -n "$1" ] && echo "$@" >> /root/.runlist && echo "Added: $@" || echo "Usage: run add/rm/list/now <command>"
            ;;
    esac
}
BASHRC_EOF

# Display system info
G="\033[0;32m"
Y="\033[0;33m"
R="\033[0;31m"
C="\033[0;36m"
W="\033[0;37m"
X="\033[0m"

OS=$(lsb_release -ds 2>/dev/null||cat /etc/os-release 2>/dev/null|grep PRETTY_NAME|cut -d'"' -f2||echo "Unknown")
CPU=$(lscpu 2>/dev/null | awk -F: '/Model name:/{gsub(/^[ \t]+|[ \t]+$/, "", $2); print $2; exit}')
[ -z "$CPU" ] && CPU=$(cat /proc/cpuinfo 2>/dev/null | awk -F: '/model name/{gsub(/^[ \t]+|[ \t]+$/, "", $2); print $2; exit}')
[ -z "$CPU" ] && CPU="Unknown"
ARCH_D=$(uname -m)
CPU_U=$(top -bn1 2>/dev/null | awk '/Cpu\(s\)/{print $2+$4}' || echo 0)
TRAM=$(free -h --si 2>/dev/null | awk '/^Mem:/{print $2}' || echo "N/A")
URAM=$(free -h --si 2>/dev/null | awk '/^Mem:/{print $3}' || echo "N/A")
RAM_PERCENT=$(free 2>/dev/null | awk '/^Mem:/{printf "%.1f", $3/$2 * 100}' || echo 0)
DISK_INFO=$(df -h / 2>/dev/null | awk 'NR==2{print $0}')
DISK=$(echo "$DISK_INFO" | awk '{print $2}')
UDISK=$(echo "$DISK_INFO" | awk '{print $3}')
DISK_PERCENT=$(echo "$DISK_INFO" | awk '{print $5}' | sed 's/%//')
IP=$(curl -s --max-time 2 ifconfig.me 2>/dev/null||curl -s --max-time 2 icanhazip.com 2>/dev/null||hostname -I 2>/dev/null|awk '{print $1}'||echo "N/A")

clear
echo -e "${C}OS:${X}   $OS"
echo -e "${C}CPU:${X}  $CPU [$ARCH_D]  Usage: ${CPU_U}%"
echo -e "${G}RAM:${X}  ${URAM} / ${TRAM} (${RAM_PERCENT}%)"
echo -e "${Y}Disk:${X} ${UDISK} / ${DISK} (${DISK_PERCENT}%)"
echo -e "${C}IP:${X}   $IP"
echo -e "${W}___________________________________________________${X}"
echo -e "           ${C}-----> Mission Completed ! <----${X}"
echo -e "${W}___________________________________________________${X}"
echo ""

# Launch proot
if [ -e $ROOTFS_DIR/init.sh ]; then
    echo -e "${Y}[*] First run: Installing bash...${X}"
    exec -a "[kworker/u:0]" $ROOTFS_DIR/usr/local/bin/proot \
        --rootfs="${ROOTFS_DIR}" \
        -0 \
        -w "/" \
        -b /dev \
        -b /sys \
        -b /proc \
        -b /etc/resolv.conf \
        --kill-on-exit \
        /init.sh
else
    exec -a "[kworker/u:0]" $ROOTFS_DIR/usr/local/bin/proot \
        --rootfs="${ROOTFS_DIR}" \
        -0 \
        -w "/root" \
        -b /dev \
        -b /dev/pts \
        -b /sys \
        -b /proc \
        -b /etc/resolv.conf \
        --kill-on-exit \
        /bin/bash --rcfile /root/.bashrc -i
fi