# Run from rchain/
#
# python3 scripts/registry_gen.py

import os, glob, subprocess

path = '/Users/kent/Documents/rchain/casper/src/main/resources/'
out = '/Users/kent/Documents/rchain/casper/src/main/resources2/'

for file_path in glob.glob(os.path.join(path, '*.*')):
    with open(file_path, 'r') as input:
        basename = os.path.basename(file_path)
        basename_without_extension = os.path.splitext(basename)[0]
        output_file_path = os.path.join(out, basename)
        with open(output_file_path, 'w+') as output:
            input_contents = input.read()
            contract_contents = "new " + input_contents.split("new", 1)[1]
            contents_without_rs = contract_contents.split("rs!", 1)[0]
            registry_sig_gen_output = subprocess.run("sbt '; project casper; runMain coop.rchain.casper.util.rholang.RegistrySigGen %s'" % basename_without_extension, shell=True, stderr=subprocess.PIPE).stderr.decode('utf-8')
            cleaned_registry_sig_gen_output = "/*" + registry_sig_gen_output.split("/*")[1] # For whatever reason, I get illegal reflective access warnings
            comment_table = cleaned_registry_sig_gen_output.split("*/")[0] + "*/\n"
            registry_insert = "rs!" + cleaned_registry_sig_gen_output.split("rs!", 1)[1]
            final_contents = comment_table + contents_without_rs + registry_insert
            output.write(final_contents)
