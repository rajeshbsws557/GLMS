#!/bin/bash
wget -q https://files.phpmyadmin.net/phpMyAdmin/5.2.1/phpMyAdmin-5.2.1-all-languages.zip -O /tmp/pma.zip
unzip -q /tmp/pma.zip -d /usr/share/
mv /usr/share/phpMyAdmin-5.2.1-all-languages /usr/share/phpmyadmin
cp /usr/share/phpmyadmin/config.sample.inc.php /usr/share/phpmyadmin/config.inc.php
sed -i "s/cfg\['blowfish_secret'\] = '';/cfg\['blowfish_secret'\] = 'o72P8v4e!1q6Y2@8r9!t#w9f5v9g0m8k5';/" /usr/share/phpmyadmin/config.inc.php
chown -R www-data:www-data /usr/share/phpmyadmin
mysql -u root -e "CREATE USER IF NOT EXISTS 'admin'@'localhost' IDENTIFIED BY 'admin123'; GRANT ALL PRIVILEGES ON *.* TO 'admin'@'localhost' WITH GRANT OPTION; FLUSH PRIVILEGES;"
