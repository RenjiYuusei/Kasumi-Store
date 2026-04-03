with open('app/build.gradle', 'r', encoding='utf-8') as f:
    content = f.read()

content = content.replace('versionCode 11', 'versionCode 12')
content = content.replace('versionName "1.5.4"', 'versionName "1.5.5"')

with open('app/build.gradle', 'w', encoding='utf-8') as f:
    f.write(content)
