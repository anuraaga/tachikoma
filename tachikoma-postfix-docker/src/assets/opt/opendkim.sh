#!/bin/bash -eu

adduser postfix opendkim

if ! ls /etc/opendkim/domainkeys/*._domainkey.*.private | grep -q domain; then
    echo "No domain keys matching pattern /etc/opendkim/domainkeys/*._domainkey.*.private was found"
    echo "Skipping dkim configuration and startup"
    exit 0
fi


cat >> /etc/opendkim.conf <<EOF
AutoRestart             Yes
AutoRestartRate         10/1h
UMask                   002
Syslog                  yes
SyslogSuccess           Yes
LogWhy                  Yes
Canonicalization        relaxed/relaxed
KeyTable                refile:/etc/opendkim/KeyTable
SigningTable            refile:/etc/opendkim/SigningTable
Mode                    sv
PidFile                 /var/run/opendkim/opendkim.pid
SignatureAlgorithm      rsa-sha256
UserID                  opendkim:opendkim
Socket                  local:/var/spool/postfix/opendkim/opendkim.sock
EOF

echo -n >/etc/opendkim/KeyTable
echo -n >/etc/opendkim/SigningTable
for A in /etc/opendkim/domainkeys/*._domainkey.*.private; do
   nsrecord="${A%%.private}";
   nsrecord="${nsrecord##*/}";
   selector="${nsrecord%%._domainkey.*}";
   domain="${nsrecord##*._domainkey.}";
   echo "$nsrecord $domain:$selector:$A" >>/etc/opendkim/KeyTable;
   echo "*@$domain $nsrecord" >>/etc/opendkim/SigningTable;
done


mkdir -p /var/spool/postfix/opendkim

chown opendkim:opendkim /var/spool/postfix/opendkim
chmod 0750 /var/spool/postfix/opendkim

chown opendkim:opendkim $(find /etc/opendkim/domainkeys -iname *.private)

chmod 400 $(find /etc/opendkim/domainkeys -iname *.private)

exec /usr/sbin/opendkim -f