#!/bin/bash
BACKUP_DIR=/root/cashbook-python/backups
DB_USER=cashbook
DB_PASS=cashbook123
DB_NAME=cashbook
DATE=$(date +%Y%m%d-%H%M)
FILE="$BACKUP_DIR/cashbook-$DATE.sql.gz"

mysqldump -u $DB_USER -p$DB_PASS $DB_NAME 2>/dev/null | gzip > $FILE
echo "$FILE" >> $BACKUP_DIR/backup.log

# Keep last 7 days
find $BACKUP_DIR -name 'cashbook-*.sql.gz' -mtime +7 -delete
